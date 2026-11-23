package dev.locus.core.agent.integrations

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.SecureRandom

/**
 * The hosts a page can be published to from the UI (docs/design/publish.md): the deploy integrations the
 * user set up in Settings → Integrations. [canUnpublish] is true for all three: each publish gets its own
 * site (Netlify), Worker (Cloudflare) or project (Vercel), so removing it removes nothing else.
 */
enum class PublishHost(val id: String, val integration: Integration, val canUnpublish: Boolean = true) {
    NETLIFY("netlify", Integrations.netlify),
    CLOUDFLARE("cloudflare", Integrations.cloudflare),
    VERCEL("vercel", Integrations.vercel),
    ;

    companion object {
        fun byId(id: String): PublishHost? = entries.firstOrNull { it.id == id }
    }
}

/** Where a published page lives: [site] is what an update or unpublish names (a site ID, Worker or project name). */
data class PublishedSite(val host: PublishHost, val site: String, val url: String?)

/**
 * Publishes a folder the app built (a chat or an artifact as a static page) through the same deploy code
 * the agent's tools use (`netlifyDeployFolder`, `CloudflareDeployStatic.deploy`, `vercelDeployFolder`), so
 * there is one HTTP path per provider. It's the user's own action in the UI: no tool, no approval card,
 * nothing in any chat's tool list.
 *
 * - Netlify: a production deploy to a site of its own, `https://NAME.netlify.app`.
 * - Cloudflare: a static-assets Worker of its own (tagged `locus-static`), `https://NAME.SUBDOMAIN.workers.dev`.
 * - Vercel: a production deployment of a project of its own with no framework; the URL is the project's
 *   production domain (`/v9/projects/{name}/domains?production=true`), which Vercel's default Standard
 *   Protection leaves public. A generated deployment URL would ask visitors to sign in to Vercel.
 */
class SitePublisher(http: HttpClient, private val secrets: SecretResolver) {
    private val netlify = NetlifyApi(http, secrets)
    private val cloudflare = CloudflareApi(http, secrets)
    private val vercel = VercelApi(http, secrets)

    /** The hosts with a key saved, in a fixed order. */
    suspend fun configured(): List<PublishHost> =
        PublishHost.entries.filter { !secrets.resolve(it.integration.secret).isNullOrBlank() }

    /**
     * Deploys every file under [folder] to [site] on [host]: a new site when [update] is false (its name is
     * [site]), or the existing one it names. Throws [ToolException] with a message for the user.
     */
    suspend fun publish(host: PublishHost, folder: File, site: String, update: Boolean): PublishedSite {
        val files = collect(folder)
        return when (host) {
            PublishHost.NETLIFY -> {
                val d = netlifyDeployFolder(netlify, files, site, production = true, createSite = !update, title = "Published from Locus")
                PublishedSite(host, d.siteId, d.url)
            }
            PublishHost.CLOUDFLARE -> {
                if (!CloudflareDeployStatic.WORKER_NAME.matches(site)) throw ToolException("Not a valid Worker name: $site")
                val d = CloudflareDeployStatic.deploy(cloudflare, site, spa = false, folder = files)
                d.url ?: throw ToolException(
                    "Cloudflare took the page, but the account has no workers.dev subdomain yet, so it has no public address. " +
                        "Open Workers & Pages in the Cloudflare dashboard once (it sets one up), then publish again.",
                )
                PublishedSite(host, site, d.url)
            }
            PublishHost.VERCEL -> {
                vercelDeployFolder(vercel, site, production = true, redeploy = null, folder = files, framework = "static")
                PublishedSite(host, site, vercelProductionUrl(site))
            }
        }
    }

    /**
     * Removes what [publish] made: the Netlify site, the Cloudflare Worker (only one tagged `locus-static`) or
     * the Vercel project. One that is already gone counts as removed.
     */
    suspend fun unpublish(published: PublishedSite) {
        when (published.host) {
            PublishHost.NETLIFY -> {
                if (!NetlifyApi.UUID.matches(published.site)) throw ToolException("Not a Netlify site ID: ${published.site}")
                goneIsFine { netlify.call(HttpMethod.Delete, "/sites/${published.site}") }
            }
            PublishHost.CLOUDFLARE -> {
                if (!CloudflareDeployStatic.WORKER_NAME.matches(published.site)) throw ToolException("Not a valid Worker name: ${published.site}")
                val account = cloudflare.accountId()
                val existing = cloudflare.call(HttpMethod.Get, "/accounts/$account/workers/scripts")["result"].objects()
                    .firstOrNull { it.field("id") == published.site } ?: return
                val tags = (existing["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (CloudflareDeployStatic.TAG !in tags) {
                    throw ToolException("The Worker ${published.site} wasn't made by Locus any more, so Locus won't delete it. Remove it in the Cloudflare dashboard.")
                }
                goneIsFine { cloudflare.call(HttpMethod.Delete, "/accounts/$account/workers/scripts/${published.site}") }
            }
            PublishHost.VERCEL -> {
                if (!VERCEL_PROJECT.matches(published.site)) throw ToolException("Not a Vercel project name: ${published.site}")
                goneIsFine { vercel.call(HttpMethod.Delete, "/v9/projects/${published.site}") }
            }
        }
    }

    /** The project's production domain, preferring its `*.vercel.app` one; null while Vercel hasn't assigned one. */
    private suspend fun vercelProductionUrl(project: String): String? {
        val domains = runCatching {
            vercel.call(HttpMethod.Get, "/v9/projects/$project/domains", mapOf("production" to true)).asObj()?.get("domains").objects()
        }.getOrDefault(emptyList())
        val names = domains.mapNotNull { it.field("name") }
        return (names.firstOrNull { it.endsWith(".vercel.app") } ?: names.firstOrNull())?.let { "https://$it" }
    }

    private suspend fun goneIsFine(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ToolException) {
            if (e.message?.startsWith("Not found") != true) throw e
        }
    }

    companion object {
        private val VERCEL_PROJECT = Regex("^[a-z0-9][a-z0-9-]{0,99}$")
        private val random = SecureRandom()

        /**
         * A new site's name: `locus-`, the title in lowercase ASCII letters and digits (at most 30), and six
         * random characters, so it's readable, never taken by an earlier publish, and valid on all three hosts
         * (Netlify and Cloudflare: 63 characters of a-z, 0-9 and dashes; Vercel: 100).
         */
        fun newSiteName(title: String, suffix: String = randomSuffix()): String {
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).trim('-')
            return listOf("locus", slug, suffix).filter { it.isNotEmpty() }.joinToString("-")
        }

        private fun randomSuffix(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        /** Every file under [folder], through the same collector (and limits) the deploy tools use. */
        internal fun collect(folder: File): SiteFolder {
            val parent = folder.canonicalFile.parentFile ?: throw ToolException("Nothing to publish.")
            return collectSiteFiles(Workspace(parent, File(parent, ".publish-trash")), folder.name)
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
