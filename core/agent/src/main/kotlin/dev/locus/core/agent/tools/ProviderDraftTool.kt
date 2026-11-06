package dev.locus.core.agent.tools

import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.Taint
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.model.HeaderRules
import dev.locus.core.model.HeaderVerdict
import dev.locus.core.model.ProviderDraft
import dev.locus.core.model.ProviderDraftApi
import dev.locus.core.model.ProviderDrafts
import dev.locus.core.model.ServiceAddress
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Where provider drafts go (docs/design/provider-drafts.md). The app's is the settings file's
 * `providerDrafts`, which nothing routes requests to; approving one is only possible in the review sheet.
 */
interface ProviderDraftStore {
    /** Keeps [draft], replacing a pending one with the same API type and address; throws [ToolException] when too many wait. */
    suspend fun save(draft: ProviderDraft): ProviderDraft

    /** The title of chat [conversationId], for "Drafted by the agent in …"; null when unknown. */
    suspend fun chatTitle(conversationId: String): String? = null

    /** Values in the vault: a draft that holds one of them is refused like any other key. */
    suspend fun vaultValues(): Collection<String> = emptyList()
}

/**
 * provider_draft: the agent proposes a model provider (non-secret fields only) for the user to review,
 * key in hand, in Settings → Models. Discovery-only ([dev.locus.core.agent.ToolDiscovery.DISCOVERY_ONLY]),
 * so chats made before it keep their tool list and cached prefix.
 *
 * What it can't do, by construction: take a key (no such argument; key-like values anywhere are refused),
 * point at a URL with a login or query in it, address plain http outside this phone and the local network,
 * or make anything usable. The draft is never routed to, never the default and never sealed as a provider.
 */
class ProviderDraftTool(
    private val store: ProviderDraftStore,
    private val ids: () -> String = { "draft-" + java.util.UUID.randomUUID() },
    private val now: () -> Long = System::currentTimeMillis,
) : Tool {
    override val risk = ToolRisk.WRITE

    override val spec = ToolSpec(
        ProviderDrafts.TOOL,
        "Draft a model provider (an AI API endpoint for Locus's chats) for the user to review in Settings → Models. Use this when the user " +
            "asks to add or set up a provider, a model API, a gateway or a local model server (Ollama, LM Studio, llama.cpp, vLLM); " +
            "don't tell them it can't be done. Give only non-secret details: a display name, the API type, the base URL, optionally " +
            "model ids and non-secret headers. There is no key argument, and any value that looks like an API key, token or password is " +
            "refused: the user pastes the key themselves while reviewing, so you never see it. Local servers usually need no key. " +
            "The draft does nothing until the user approves it there; it is never used by any chat, helper or fallback before that, " +
            "and it never becomes the default by itself. Prefer the provider's documented base URL; ask the user if you're unsure.",
        schema,
    )

    override fun describe(args: JsonObject): String {
        val host = args.str("base_url")?.let { dev.locus.core.model.LocalNetwork.hostOf(it) }
            ?: ProviderDraftApi.of(args.str("api_type"))?.defaultBaseUrl?.let { dev.locus.core.model.LocalNetwork.hostOf(it) }
        return "Draft provider “${args.str("name").orEmpty().take(ProviderDrafts.MAX_NAME)}”" + (host?.let { " at $it" } ?: "") + " for your review"
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val draft = parse(args)
        val ctx = currentCoroutineContext()[ToolCallContext]
        val taint = ctx?.taint
        val named = taint?.trustedHosts?.let { Taint.trusted(draft.host, it) } ?: false
        val saved = store.save(
            draft.copy(
                conversationId = ctx?.conversationId,
                chatTitle = ctx?.conversationId?.let { runCatching { store.chatTitle(it) }.getOrNull() },
                taintSources = taint?.sources.orEmpty().sorted(),
                userNamedHost = named,
            ),
        )
        return ToolResult(
            buildString {
                append("Drafted provider “${saved.name}” (${saved.api.label}, ${saved.baseUrl}). It is NOT active: no chat, helper or fallback uses it, ")
                append("and it isn't the default. Tell the user to review it in Settings → Models, where it shows as Needs review: ")
                append(if (saved.local) "they can test it and approve it (a local server usually needs no key). " else "they paste the API key there, test it and approve it. ")
                append("Don't ask them for the key in the chat.")
                if (saved.cleartext) append(" It uses plain http, which is only acceptable on their own network; the review says so.")
            },
        )
    }

    /** The draft [args] describe, or a [ToolException] saying what's wrong (nothing is saved then). */
    internal suspend fun parse(args: JsonObject): ProviderDraft {
        args.keys.firstOrNull { it !in ARGUMENTS }?.let { key ->
            throw ToolException(
                if (KEY_NAME.containsMatchIn(key)) "There is no “$key” argument, on purpose: keys never go through the chat. Draft the provider without it; the user pastes the key in Settings → Models when reviewing."
                else "Unknown argument “$key”. provider_draft takes ${ARGUMENTS.joinToString()}.",
            )
        }
        val api = ProviderDraftApi.of(args.str("api_type"))
            ?: throw ToolException("api_type must be one of ${ProviderDraftApi.entries.joinToString { it.id }}.")
        val rawName = args.str("name")?.trim().orEmpty()
        val rawUrl = args.str("base_url")?.trim().orEmpty().ifBlank { api.defaultBaseUrl.orEmpty() }
        val models = when (val m = args["models"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> m.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() ?: throw ToolException("models must be a list of model id strings.") }
            else -> throw ToolException("models must be a list of model id strings.")
        }.filter { it.isNotEmpty() }.distinct()
        val headers = when (val h = args["headers"]) {
            null, is JsonNull -> emptyMap()
            is JsonObject -> h.mapValues { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolException("Header “$k” must have a string value.") }
            else -> throw ToolException("headers must be an object of header names to values.")
        }

        // First, before any other check can echo a value back: a key in any field the model gave, header names included.
        val vault = runCatching { store.vaultValues() }.getOrDefault(emptyList())
        val values = listOf("name" to rawName, "base_url" to rawUrl) + models.map { "models" to it } + headers.flatMap { (k, v) -> listOf("headers" to k, "headers" to v) }
        values.firstOrNull { (_, v) -> KeyLike.looksLikeKey(v, vault) }?.let { (field, _) ->
            throw ToolException(
                "Refused: the $field value looks like an API key, token or password. Keys never go through the chat or into a draft. " +
                    "Draft the provider without it; the user pastes the key in Settings → Models when reviewing.",
            )
        }

        val name = rawName.take(ProviderDrafts.MAX_NAME).ifBlank { api.label }
        if (rawUrl.isBlank()) throw ToolException("base_url is required for ${api.id}: the server's address, e.g. http://192.168.1.10:11434/v1 or https://api.example.com/v1.")
        if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(rawUrl)) throw ToolException("base_url must start with https:// (or http:// for a server on this phone or the local network).")
        if (models.size > ProviderDrafts.MAX_MODELS) throw ToolException("At most ${ProviderDrafts.MAX_MODELS} model ids.")
        models.firstOrNull { it.length > 200 || it.any(Char::isWhitespace) }?.let { throw ToolException("“${it.take(60)}” isn't a model id.") }
        if (headers.size > ProviderDrafts.MAX_HEADERS) throw ToolException("At most ${ProviderDrafts.MAX_HEADERS} headers.")
        for ((k, v) in headers) {
            if (KEY_NAME.containsMatchIn(k)) throw ToolException("“$k” looks like a credential header, so it can't be drafted: the user adds keys in Settings → Models when reviewing.")
            when (val verdict = HeaderRules.check(k)) {
                HeaderVerdict.Allowed -> Unit
                is HeaderVerdict.Credential -> throw ToolException("“$k” carries a credential, so it can't be drafted: the user adds keys in Settings → Models when reviewing.")
                is HeaderVerdict.Forbidden -> throw ToolException(verdict.reason)
            }
            HeaderRules.checkValue(v)?.let { throw ToolException("Header “$k”: $it") }
        }

        val checked = ServiceAddress.check(rawUrl)
        val url = checked.url ?: throw ToolException("base_url: ${addressProblem(checked.error.orEmpty())}")
        return ProviderDraft(
            id = ids(),
            name = name,
            api = api,
            baseUrl = url,
            models = models,
            headers = headers.toList().sortedBy { it.first.lowercase() }.toMap(),
            createdAt = now(),
        )
    }

    /** ServiceAddress speaks to a person filling a form; the model gets the same rule in its terms. */
    private fun addressProblem(error: String) = when {
        "user name or password" in error -> "a user name or password in the address isn't allowed. Give the address alone; the user adds the key when reviewing."
        "after ? or #" in error -> "leave out the query and fragment (anything after ? or #); keys never go in the address."
        else -> error
    }

    companion object {
        private val ARGUMENTS = listOf("name", "api_type", "base_url", "models", "headers")

        /** Argument names that ask for a credential: the refusal says why there's no such argument. */
        private val KEY_NAME = Regex("(?i)key|token|secret|password|auth|credential|bearer")

        private val schema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Display name, e.g. \"Groq\" or \"Ollama on my PC\".")
                }
                putJsonObject("api_type") {
                    put("type", "string")
                    putJsonArray("enum") { ProviderDraftApi.entries.forEach { add(it.id) } }
                    put(
                        "description",
                        "The wire protocol: anthropic (Anthropic Messages), openai_responses (OpenAI Responses), openai_chat (any OpenAI-compatible " +
                            "Chat Completions server: Groq, Mistral, Together, DeepSeek, Ollama, LM Studio, llama.cpp, vLLM…), openrouter, or gemini " +
                            "(Google's OpenAI-compatible endpoint).",
                    )
                }
                putJsonObject("base_url") {
                    put("type", "string")
                    put(
                        "description",
                        "The API's base URL, e.g. https://api.groq.com/openai/v1 or http://192.168.1.10:11434/v1. https, or http only for this phone " +
                            "or the local network. No user name, password, query or key in it. Optional for anthropic, openai_responses, openrouter and gemini.",
                    )
                }
                putJsonObject("models") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Optional model ids to offer, the one to use first.")
                }
                putJsonObject("headers") {
                    put("type", "object")
                    putJsonObject("additionalProperties") { put("type", "string") }
                    put("description", "Optional extra request headers that aren't secret (e.g. a site name for OpenRouter's rankings). Never Authorization or a key.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("name")); add(JsonPrimitive("api_type")) }
            put("additionalProperties", false)
        }
    }
}

/**
 * Whether a value looks like a credential: the [SecretScrubber]'s patterns (and any vault value) find
 * something in it, or a few more key prefixes and a long random-looking token do.
 */
internal object KeyLike {
    private val EXTRA = listOf(
        Regex("""\bgsk_[A-Za-z0-9]{20,}"""), // Groq
        Regex("""\bxai-[A-Za-z0-9]{20,}"""), // xAI
        Regex("""\bhf_[A-Za-z0-9]{20,}"""), // Hugging Face
        Regex("""\bpplx-[A-Za-z0-9]{20,}"""), // Perplexity
        Regex("""\bnvapi-[A-Za-z0-9_\-]{20,}"""), // NVIDIA
        Regex("""\bsk_[A-Za-z0-9]{20,}"""),
        Regex("""\b(?:api|key|token)[_-]?[A-Za-z0-9]{24,}""", RegexOption.IGNORE_CASE),
        Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""), // a JWT
    )

    /** A long mixed-case-and-digit token with no separators a model id or path would have. */
    private val RANDOM = Regex("""[A-Za-z0-9_\-]{32,}""")

    fun looksLikeKey(value: String, vault: Collection<String> = emptyList()): Boolean {
        if (value.isBlank()) return false
        if (SecretScrubber(vault).scrub(value) != value) return true
        if (EXTRA.any { it.containsMatchIn(value) }) return true
        return RANDOM.findAll(value).any { m ->
            val t = m.value
            t.any(Char::isUpperCase) && t.any(Char::isLowerCase) && t.any(Char::isDigit)
        }
    }
}
