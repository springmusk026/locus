package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * The kinds of endpoint the agent may draft (docs/design/provider-drafts.md): the cloud wire protocols
 * Locus speaks, with OpenRouter and Gemini named because each needs a particular address. Never the
 * ChatGPT plan (a browser sign-in) or the phone's own models (nothing to address).
 */
@Serializable
enum class ProviderDraftApi(
    /** The value the model passes as `api_type`. */
    val id: String,
    val label: String,
    val format: ApiFormat,
    /** The address used when the model gives none; null: an address is required. */
    val defaultBaseUrl: String?,
) {
    ANTHROPIC("anthropic", "Anthropic Messages", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com"),
    OPENAI_RESPONSES("openai_responses", "OpenAI Responses", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1"),
    OPENAI_CHAT("openai_chat", "OpenAI-compatible (Chat Completions)", ApiFormat.OPENAI_CHAT, null),
    OPENROUTER("openrouter", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1"),
    GEMINI("gemini", "Gemini (OpenAI-compatible)", ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai"),
    ;

    companion object {
        fun of(id: String?): ProviderDraftApi? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * A model provider the agent drafted with `provider_draft`, waiting for the user in Settings → Models.
 *
 * It is inert: it lives in [AppSettings.providerDrafts], never in [AppSettings.providers], so no chat,
 * helper, fallback or model list can reach it, and it holds no key (the tool has no key argument and
 * refuses key-like values). Approving it in the review sheet creates an ordinary provider, sealed like
 * one the user added; discarding it removes it. Drafts are covered by the endpoint seal, so one planted
 * or edited outside Locus (its taint flag cleared, say) breaks the seal and every draft is dropped.
 */
@Serializable
data class ProviderDraft(
    val id: String,
    val name: String,
    val api: ProviderDraftApi,
    /** Checked with [ServiceAddress.check]: https, or http only to this phone or the local network. */
    val baseUrl: String,
    /** Model ids the agent suggested, first one preselected; may be empty. */
    val models: List<String> = emptyList(),
    /** Extra request headers, names to values; never credentials (the tool refuses those). */
    val headers: Map<String, String> = emptyMap(),
    /** The chat that drafted it; null when it came from elsewhere (a helper's chat is its parent's). */
    val conversationId: String? = null,
    /** That chat's title when the draft was made, for "Drafted by the agent in …" once the chat is gone or renamed. */
    val chatTitle: String? = null,
    /** What outside content the run had read when it drafted this (web_read, email_read…): the review warns. */
    val taintSources: List<String> = emptyList(),
    /** The user named this host in the chat (or set it up in Locus before), so it isn't a stranger to them. */
    val userNamedHost: Boolean = false,
    val createdAt: Long = 0,
) {
    val tainted: Boolean get() = taintSources.isNotEmpty()

    /** The destination host the review shows first. */
    val host: String get() = LocalNetwork.hostOf(baseUrl).orEmpty()

    /** On this phone or the local network: Ollama, LM Studio, llama.cpp, which usually take no key. */
    val local: Boolean get() = isLocalAddress(baseUrl)

    /** Plain http (only ever allowed to a local host): the review shows the warning. */
    val cleartext: Boolean get() = baseUrl.lowercase().startsWith("http://")

    override fun toString() = "ProviderDraft(id=$id, api=${api.id}, host=$host, models=${models.size}, headers=${headers.keys}, tainted=$tainted)"

    companion object {
        fun isLocalAddress(url: String): Boolean = LocalNetwork.hostOf(url)?.let { LocalNetwork.isLoopback(it) || LocalNetwork.isLocalHost(it) } ?: false
    }
}

object ProviderDrafts {
    const val TOOL = "provider_draft"

    /** Drafts waiting at once; the tool refuses more until the user reviews some. */
    const val MAX_PENDING = 5
    const val MAX_MODELS = 20
    const val MAX_HEADERS = 8
    const val MAX_NAME = 60

    /**
     * Hosts Locus knows as model providers without asking anyone: the built-in presets' and the first-party
     * endpoints the models.dev catalogue lists without an address. The review adds the cached catalogue's
     * own `api` hosts when the catalogue is on.
     */
    val BUILT_IN_HOSTS: Set<String> = buildSet {
        ProviderPresets.all.map { it.baseUrl }.filter { it.isNotBlank() && !ProviderDraft.isLocalAddress(it) }.mapNotNull(LocalNetwork::hostOf).forEach(::add)
        ProviderDraftApi.entries.mapNotNull { it.defaultBaseUrl }.mapNotNull(LocalNetwork::hostOf).forEach(::add)
        addAll(listOf("api.anthropic.com", "api.openai.com", "generativelanguage.googleapis.com", "api.groq.com", "api.mistral.ai", "api.x.ai", "api.deepseek.com", "openrouter.ai"))
    }

    /** A provider_draft call as the chat's card shows it: the name and destination host it asked for. */
    data class Call(val name: String, val host: String)

    /** [argumentsJson] of a provider_draft call as a [Call]; null when it can't be read. */
    fun call(argumentsJson: String): Call? {
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (args[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val api = ProviderDraftApi.of(str("api_type"))
        val url = str("base_url")?.takeIf { it.isNotEmpty() } ?: api?.defaultBaseUrl ?: return null
        val host = LocalNetwork.hostOf(url) ?: return null
        return Call(str("name")?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: api?.label ?: "Provider", host)
    }

    /**
     * What the run had read, in the review's words: "a web page and an email". [sources] are the taint
     * sources a run records (tool names, or already-worded ones such as "another app (Tasker)").
     */
    fun describeSources(sources: List<String>): String {
        val words = sources.map { s ->
            when {
                s.startsWith("browser_") || s in setOf("web_read", "fetch_url", "web_search", "wikipedia") -> "a web page"
                s.startsWith("email_") -> "an email"
                s == "mcp_call" || s == "mcp_list_tools" -> "an MCP server's reply"
                s == "connector_call" -> "a connector's reply"
                s.startsWith("github_") -> "a GitHub issue or file"
                s == "run_command" || s.startsWith("linux_service_") || s.startsWith("ssh_") -> "command output"
                s.startsWith("agent_") -> "a helper's report"
                else -> s
            }
        }.distinct()
        return when (words.size) {
            0 -> "outside content"
            1 -> words[0]
            else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
        }
    }

    /** Whether [host] is one of [BUILT_IN_HOSTS] or [catalogHosts] (or a subdomain of one). */
    fun knownHost(host: String, catalogHosts: Set<String> = emptySet()): Boolean {
        val h = host.lowercase().trimEnd('.').removePrefix("www.")
        if (h.isEmpty()) return false
        return (BUILT_IN_HOSTS + catalogHosts).any { k -> h == k || h.endsWith(".$k") }
    }

    /**
     * The provider an approved draft becomes, with the user's edits from the review sheet: a new id (nothing
     * that pointed at the draft carries over), its address confirmed, extra headers by name only (their
     * values go to the vault). Exactly what the provider editor saves for one the user added.
     */
    fun approved(
        draft: ProviderDraft,
        newId: String,
        name: String,
        api: ProviderDraftApi,
        baseUrl: String,
        model: String,
        requiresKey: Boolean,
        headerNames: List<String>,
    ): ProviderConfig = ProviderConfig(
        id = newId,
        name = name.trim().ifBlank { draft.name },
        format = api.format,
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        requiresKey = requiresKey,
        network = ProviderNetwork(headerNames = headerNames),
        addressUnconfirmed = false,
    )
}
