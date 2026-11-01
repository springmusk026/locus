package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.Fit
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.ModelCapabilityResolver
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.llm.ModelRequirements
import dev.locus.core.model.Effort
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part

/**
 * What this chat's next request needs from a model (docs/design/model-capabilities.md): tools (every
 * chat declares them), vision when the context the model sees or the message being written carries an
 * image, room for the context so far, and reasoning when the chat chose a thinking effort of its own.
 * [messages] is the shown branch; only what's after the last compaction checkpoint is sent.
 */
internal fun chatNeeds(messages: List<Message>, pending: List<Part> = emptyList(), chosenEffort: Effort? = null): ModelRequirements {
    val sent = dev.locus.core.agent.Compaction.modelContext(messages)
    return ModelRequirements.of(
        ChatRequest(system = "", messages = sent, tools = emptyList()),
        reasoning = chosenEffort != null && chosenEffort != Effort.OFF,
        pending = pending,
    ).copy(tools = true)
}

/**
 * [chatNeeds] of the saved messages, with the composer's [pending] attachments and typed [text] added,
 * and reasoning wanted when the chat chose an effort of its own ([chosenEffort]) other than Off.
 */
internal fun ModelRequirements.withComposer(pending: List<Part.Attachment>, chosenEffort: Effort?, text: String = ""): ModelRequirements = copy(
    images = images || pending.any(ModelRequirements::isImage),
    contextTokens = contextTokens + pending.sumOf { if (ModelRequirements.isImage(it)) ModelRequirements.IMAGE_TOKENS else 50 } + (text.length + 3) / 4,
    reasoning = chosenEffort != null && chosenEffort != Effort.OFF,
)

/** What [id] of [p] can do, from the provider's list, the catalogue (when on) and the built-in rules. */
internal fun capabilitiesOf(p: ChatProvider, id: String, lists: Map<String, ModelList>, catalog: ModelCatalogIndex?): ModelCapabilities =
    ModelCapabilityResolver.resolve(
        format = p.format,
        baseUrl = p.baseUrl,
        modelId = id,
        listed = (lists[p.id] as? ModelList.Loaded)?.models?.firstOrNull { it.id == id },
        catalog = catalog?.lookup(p.baseUrl, p.format, id),
        configuredContext = p.contextWindow?.takeIf { id == p.model },
    )

/**
 * Shown instead of sending when the chat's own model is known not to serve the message: images
 * attached to a model without vision, a context larger than its window, a model without tool calling.
 * [suggestion] is a model the user has picked before (favourites, recents, backups, then each
 * provider's default) known to serve it. [text] is what was typed, given back on Cancel.
 */
internal data class SendWarning(
    val model: String,
    val reason: String,
    val suggestion: ModelRef? = null,
    val suggestionTitle: String? = null,
    val text: String = "",
) {
    val title: String get() = "$model can't take this message"
    val body: String get() = buildString {
        append("$model: $reason. Sending would most likely fail.")
        if (suggestionTitle != null) append(" $suggestionTitle can take it.") else append(" Pick a model that can in the model sheet.")
    }
}

/**
 * The warning before sending, or null when the chat's model can serve [needs] (or nothing is known
 * against it: unknown abilities never block). [candidates] are the models to suggest, in order, with
 * their display titles.
 */
internal fun sendWarning(
    needs: ModelRequirements,
    current: ModelCapabilities,
    currentLabel: String,
    candidates: List<Triple<ModelRef, String, ModelCapabilities>>,
    text: String = "",
): SendWarning? {
    val fit = current.fit(needs)
    if (fit.usable) return null
    val missing = fit.lacks.filter { it.hard }.toSet()
    // A model known to cover what the chat's model lacks, and not known to lack anything else.
    val pick = candidates.firstOrNull { (_, _, caps) ->
        val f = caps.fit(needs)
        f.usable && missing.none { it in f.unconfirmed }
    }
    return SendWarning(currentLabel, fit.reason.orEmpty(), pick?.first, pick?.second, text)
}

/**
 * The sheet's compact chips for what a model is known to do: its window ("200K"), then Tools, Vision
 * and Reasoning when known yes. A "no" or unknown shows no chip; for this chat the row says more.
 */
internal fun capabilityChips(caps: ModelCapabilities): List<String> = listOfNotNull(
    caps.contextWindow?.let { Fit.tokensLabel(it) },
    "Tools".takeIf { caps.tools == dev.locus.core.llm.Support.YES },
    "Vision".takeIf { caps.vision == dev.locus.core.llm.Support.YES },
    "Reasoning".takeIf { caps.reasoning == dev.locus.core.llm.Support.YES },
)

/** The picker's line for a model that can't serve this chat: "Can't use here: no vision". */
internal fun cantUseLabel(fit: Fit): String? = fit.reason?.let { "Can't use here: $it" }

/** The picker's quiet note for needs nobody could confirm: "Vision not confirmed". */
internal fun unconfirmedLabel(fit: Fit): String? =
    fit.unconfirmed.filter { it.hard }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }?.replaceFirstChar { it.uppercase() }?.let { "$it not confirmed" }
