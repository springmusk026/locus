package dev.locus.core.agent.integrations

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Publishes a workspace folder as a Cloudflare Workers static-assets site, using the documented
 * direct-upload flow (https://developers.cloudflare.com/workers/static-assets/direct-upload/):
 *
 * 1. POST /accounts/{id}/workers/scripts/{name}/assets-upload-session with a manifest of
 *    `"/path": {hash, size}`. Cloudflare answers with the hashes it doesn't have yet, grouped in
 *    buckets, and an upload JWT.
 * 2. POST /accounts/{id}/workers/assets/upload?base64=true once per bucket, authorised by that JWT,
 *    with one multipart field per file (name = hash, value = base64 content). The last upload
 *    returns the completion JWT. With no buckets the session JWT already is the completion JWT.
 * 3. PUT /accounts/{id}/workers/scripts/{name} with metadata `{main_module, assets: {jwt, config}}`
 *    and a tiny module ([STUB]), which creates or updates the Worker. The API requires a module
 *    (https://developers.cloudflare.com/workers/configuration/multipart-upload-metadata/); wrangler
 *    uploads a similar no-op module for assets-only projects. Static files are served before the
 *    module runs, so the module only answers requests that match no file.
 * 4. Turns on the script's workers.dev route and reports https://{name}.{subdomain}.workers.dev.
 *
 * Workers this tool creates carry the tag [TAG]; an existing Worker with code and without the tag
 * is never replaced. The vault token is used only in the Authorization header of steps 1, 3 and 4. See
 * docs/design/cloudflare-deploy.md for the research behind this.
 */
internal class CloudflareDeployStatic(private val api: CloudflareApi, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "cloudflare_deploy_static",
        "Publish a workspace folder of built, static files (e.g. dist, build or public) as a public Cloudflare Workers site at " +
            "https://NAME.SUBDOMAIN.workers.dev, and return the URL. Creates the Worker if needed and uploads only files Cloudflare " +
            "doesn't already have. Nothing is built on Cloudflare: run the build first. Set spa for single-page apps (React, Vue) " +
            "so unknown paths serve index.html. Won't replace a Worker that runs code.",
        ObjectSchema()
            .string("path", "Workspace folder to publish.")
            .string("name", "Worker name: lowercase letters, digits and dashes (it becomes part of the URL).")
            .boolean("spa", "Single-page app: serve index.html for paths with no file. Defaults to false (404 page).")
            .build(),
    )

    override fun describe(args: JsonObject) =
        "Publish ${args.str("path")} to Cloudflare as ${args.str("name")}.workers.dev" + (if (args.bool("spa") == true) " (single-page app)" else "")

    override suspend fun mustConfirm(args: JsonObject) =
        "This publishes the folder on a public workers.dev address and replaces any earlier version of this site."

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.str("name")?.trim()?.lowercase().orEmpty()
        if (!WORKER_NAME.matches(name)) {
            throw ToolException("name must be 1–63 lowercase letters, digits or dashes, starting and ending with a letter or digit.")
        }
        val spa = args.bool("spa") == true
        val folder = collectSiteFiles(workspace, args.str("path").orEmpty(), maxFiles = MAX_FILES, maxBytes = MAX_TOTAL_BYTES)
        folder.files.firstOrNull { it.size > MAX_FILE_BYTES }?.let {
            throw ToolException("${it.path} is ${it.size / 1024 / 1024} MiB; Cloudflare takes files up to 25 MiB. Shrink or remove it first.")
        }
        val assets = folder.files.map { AssetFile(it, assetHash(it.bytes(), it.path)) }
        if (spa && assets.none { it.file.path == "index.html" }) {
            throw ToolException("spa needs an index.html at the top of ${folder.path}. Is this the build output folder?")
        }

        val account = api.accountId()
        refuseCodeWorker(account, name)

        val session = api.call(HttpMethod.Post, "/accounts/$account/workers/scripts/$name/assets-upload-session", json = manifest(assets))["result"].asObj()
            ?: throw ToolException("Unexpected response from Cloudflare.")
        val uploadJwt = session.field("jwt") ?: throw ToolException("Cloudflare didn't return an upload session.")
        val buckets = (session["buckets"] as? JsonArray).orEmpty().map { b -> (b as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
        val byHash = assets.associateBy { it.hash }
        var completion = uploadJwt
        var uploaded = 0
        for (bucket in buckets.filter { it.isNotEmpty() }) {
            val form = MultiPartFormDataContent(
                formData {
                    for (hash in bucket) {
                        val asset = byHash[hash] ?: throw ToolException("Cloudflare asked for a file that isn't in the folder ($hash).")
                        append(
                            hash,
                            Base64.getEncoder().encodeToString(asset.file.bytes()),
                            Headers.build {
                                append(HttpHeaders.ContentType, contentTypeFor(asset.file.path))
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"$hash\"; filename=\"$hash\"")
                            },
                        )
                        uploaded++
                    }
                },
            )
            val result = api.call(HttpMethod.Post, "/accounts/$account/workers/assets/upload", mapOf("base64" to true), form = form, bearer = uploadJwt)["result"].asObj()
            result?.field("jwt")?.let { completion = it }
        }
        if (buckets.any { it.isNotEmpty() } && completion == uploadJwt) {
            throw ToolException("Cloudflare accepted the files but didn't confirm the upload. Run the deploy again.")
        }

        val metadata = buildJsonObject {
            put("main_module", STUB_NAME)
            put("compatibility_date", COMPATIBILITY_DATE)
            putJsonArray("tags") { add(TAG) }
            putJsonArray("bindings") { addJsonObject { put("type", "assets"); put("name", "ASSETS") } }
            putJsonObject("assets") {
                put("jwt", completion)
                putJsonObject("config") {
                    put("not_found_handling", if (spa) "single-page-application" else "404-page")
                }
            }
        }
        val script = MultiPartFormDataContent(
            formData {
                append("metadata", metadata.toString(), Headers.build { append(HttpHeaders.ContentType, "application/json") })
                append(
                    STUB_NAME, STUB,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/javascript+module")
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"$STUB_NAME\"; filename=\"$STUB_NAME\"")
                    },
                )
            },
        )
        api.call(HttpMethod.Put, "/accounts/$account/workers/scripts/$name", form = script)

        val subdomain = api.find("/accounts/$account/workers/subdomain")?.get("result").asObj()?.field("subdomain")
            ?: return ToolResult(
                "Uploaded ${folder.path} as the Worker $name, but the account has no workers.dev subdomain yet, so it has no public URL. " +
                    "Ask the user to open Workers & Pages in the Cloudflare dashboard once (it sets up the subdomain), then run this again.",
            )
        api.call(
            HttpMethod.Post, "/accounts/$account/workers/scripts/$name/subdomain",
            json = buildJsonObject { put("enabled", true); put("previews_enabled", false) },
        )
        val url = "https://$name.$subdomain.workers.dev"
        return ToolResult(
            "Published ${folder.path} to $url\n" +
                "${assets.size} files (${uploaded} uploaded, the rest already on Cloudflare)" +
                (if (spa) "; unknown paths serve index.html." else "; unknown paths serve 404.html if there is one.") +
                "\nA brand-new workers.dev address can take a minute or two to start answering.",
        )
    }

    /**
     * Refuses to overwrite a Worker that runs someone's code: replacing it with a static site would
     * delete that code. Workers this tool made carry [TAG]; assets-only Workers have no modules.
     */
    private suspend fun refuseCodeWorker(account: String, name: String) {
        val scripts = api.call(HttpMethod.Get, "/accounts/$account/workers/scripts")["result"].objects()
        val existing = scripts.firstOrNull { it.field("id") == name } ?: return
        val tags = (existing["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (TAG in tags) return
        val handlers = (existing["handlers"] as? JsonArray).orEmpty()
        if (existing.flag("has_modules") == true || handlers.isNotEmpty()) {
            throw ToolException(
                "A Worker named $name already exists and runs code; publishing static files over it would delete that code. Pick another name.",
            )
        }
    }

    internal class AssetFile(val file: SiteFile, val hash: String)

    companion object {
        val WORKER_NAME = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

        /** https://developers.cloudflare.com/workers/static-assets/#limits (paid plans allow more files). */
        const val MAX_FILES = 20_000
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
        const val COMPATIBILITY_DATE = "2026-10-01"

        /** Marks Workers made by this tool, so a later deploy may replace them. */
        const val TAG = "locus-static"
        const val STUB_NAME = "locus-static.js"

        /**
         * Runs only for requests that match no file (and, in SPA mode, aren't page navigations):
         * serves the site's 404.html when it has one, like the `404-page` setting would without code.
         */
        const val STUB = """export default {
  async fetch(request, env) {
    const page = await env.ASSETS.fetch(new URL("/404.html", request.url));
    if (page.ok) return new Response(page.body, { status: 404, headers: page.headers });
    return new Response("Not found", { status: 404, headers: { "Content-Type": "text/plain" } });
  },
};
"""

        /** The `/path` → `{hash, size}` manifest of the upload session. */
        internal fun manifest(assets: List<AssetFile>) = buildJsonObject {
            putJsonObject("manifest") {
                assets.forEach { a -> putJsonObject("/" + a.file.path) { put("hash", a.hash); put("size", a.file.size) } }
            }
        }

        /**
         * The asset hash from Cloudflare's direct-upload guide: SHA-256 over the base64 of the content
         * followed by the file extension (without the dot), as hex, first 32 characters.
         */
        internal fun assetHash(bytes: ByteArray, path: String): String {
            val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            val digest = MessageDigest.getInstance("SHA-256").digest((Base64.getEncoder().encodeToString(bytes) + extension).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        internal fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json", "map", "webmanifest" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            // Documented: lets Cloudflare serve the file without a Content-Type of ours.
            else -> "application/null"
        }
    }
}
