package dev.locus.core.data.mcp

import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.datastore.SecretStore
import java.net.URI

/**
 * "Use the key saved for <Integration>": a template can reuse the key the user already saved for the
 * built-in integration it stands in for, copied inside the vault. The value never reaches the model,
 * the UI, logs or an exception message.
 *
 * Offered only where the vendor documents that its MCP server takes the same kind of key the
 * built-in uses, sent exactly the way listed in [fits] (checked against each vendor's page, see
 * docs/design/mcp-connectors.md). If a template's auth ever changes, the offer disappears rather
 * than sending a key somewhere it doesn't belong. Sign-in-only templates (Netlify) take no key.
 */
object SavedKeys {
    /** How an auth is sent: `bearer`, or `header` / `query` with its name (lowercase). */
    data class Shape(val type: String, val name: String = "")

    /** Built-in integration id → how its key must be sent to the vendor's MCP server. */
    val fits: Map<String, Shape> = mapOf(
        // A fine-grained or classic PAT as Bearer (github-mcp-server remote docs).
        "github" to Shape("bearer"),
        // A personal access token (sbp_…) as Bearer (Supabase MCP docs, "manual authentication").
        "supabase" to Shape("bearer"),
        // An account API key as Bearer (render.com/docs/mcp-server).
        "render" to Shape("bearer"),
        // An API token as Bearer (cloudflare/mcp README).
        "cloudflare" to Shape("bearer"),
        "firecrawl" to Shape("header", "x-firecrawl-api-key"),
        "tavily" to Shape("query", "tavilyapikey"),
        "exa" to Shape("header", "x-api-key"),
    )

    /** How [key] sends its value, in [Shape] terms. */
    fun shape(key: McpTemplate.Key): Shape = when {
        key.header != null -> Shape("header", key.header.lowercase())
        key.query != null -> Shape("query", key.query.lowercase())
        else -> Shape("bearer")
    }

    /** The built-in whose saved key [template] can take, or null when it takes another kind (or none). */
    fun source(template: McpTemplate): Integration? {
        if (template.local != null) return null
        val key = template.key ?: return null
        val integration = Integrations.all.firstOrNull { it.id == template.replaces } ?: return null
        return integration.takeIf { fits[it.id] == shape(key) }
    }

    /**
     * The key itself from a built-in's saved value. Some built-ins keep extra words after the key (a
     * Cloudflare account ID, a Vercel team ID), which the MCP server must not get. Null when nothing
     * usable is there.
     */
    internal fun keyPart(saved: String): String? = saved.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Whether [url] still reaches [template]'s own host over https: the copied key goes only where the
     * template says, even if the user edited the URL's path or options.
     */
    fun sameHost(template: McpTemplate, url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host != null && uri.host.equals(template.host, ignoreCase = true) && uri.userInfo == null
    }

    /**
     * Copies [integration]'s saved key to the connector secret [to], vault to vault. Throws with a
     * message that never contains the key when there's nothing to copy.
     */
    suspend fun copy(secrets: SecretStore, integration: Integration, to: String) {
        val saved = secrets.get(SecretStore.connectorSecret(integration.secret))
            ?: throw IllegalStateException("No key is saved for ${integration.name}. Add it in Settings → Integrations, or paste one here.")
        val key = keyPart(saved) ?: throw IllegalStateException("The key saved for ${integration.name} is empty.")
        secrets.put(SecretStore.connectorSecret(to), key)
    }
}
