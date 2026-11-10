package dev.locus.feature.chat

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.Fit
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.ModelCapabilityResolver
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.llm.ModelRequirements
import dev.locus.core.llm.Need
import dev.locus.core.llm.Support
import dev.locus.core.model.Effort
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part

/**
 * What this chat's next request needs from a model (docs/design/model-capabilities.md): tools once the
 * chat has started (its tool list is fixed then; before that it follows the model picked, see
 * [dev.locus.core.data.repository.ChatOnlyRule]), vision when the context the model sees or the message
 * being written carries an image, room for the context so far, and reasoning when the chat chose a
 * thinking effort of its own. [messages] is the shown branch; only what's after the last compaction
 * checkpoint is sent. A chat-only chat needs no tools at all (the caller clears it).
 */
internal fun chatNeeds(messages: List<Message>, pending: List<Part> = emptyList(), chosenEffort: Effort? = null): ModelRequirements {
    val sent = dev.locus.core.agent.Compaction.modelContext(messages)
    return ModelRequirements.of(
        ChatRequest(system = "", messages = sent, tools = emptyList()),
        reasoning = chosenEffort != null && chosenEffort != Effort.OFF,
        pending = pending,
    ).copy(tools = messages.isNotEmpty())
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
 * [fit] is what the model lacks ([Fit.lacks]); the screen says it in its language ([fitReason]).
 * [suggestion] is a model the user has picked before (favourites, recents, backups, then each
 * provider's default) known to serve it. [text] is what was typed, given back on Cancel.
 */
internal data class SendWarning(
    val model: String,
    val fit: Fit,
    val suggestion: ModelRef? = null,
    val suggestionTitle: String? = null,
    val text: String = "",
) {
    fun title(resources: Resources): String = resources.getString(R.string.chat_sendwarning_title, model)

    fun body(resources: Resources): String {
        // The title already names the model.
        val reason = fitReason(resources, fit).orEmpty().replaceFirstChar { it.uppercase() }
        return if (suggestionTitle != null) {
            resources.getString(R.string.chat_sendwarning_body_suggest, reason, suggestionTitle)
        } else {
            resources.getString(R.string.chat_sendwarning_body_choose, reason)
        }
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
    return SendWarning(currentLabel, fit, pick?.first, pick?.second, text)
}

/** What a need is called in "Vision not confirmed": lower case, for the middle of a sentence. */
@StringRes
private fun Need.labelRes(): Int = when (this) {
    Need.TOOLS -> R.string.chat_fit_need_tools
    Need.VISION -> R.string.chat_fit_need_vision
    Need.CONTEXT -> R.string.chat_fit_need_context
    Need.REASONING -> R.string.chat_fit_need_reasoning
    Need.STRUCTURED_OUTPUT -> R.string.chat_fit_need_structured
}

/** Joins [parts] as a list in the language's own way: "a, b, c". */
private fun Resources.joinList(parts: List<String>): String =
    parts.reduceOrNull { all, next -> getString(R.string.chat_fit_list_join, all, next) }.orEmpty()

/**
 * The hard lacks of [fit] in words, as the picker and the send warning say them: "no vision",
 * "context too small (128K, needs ~150K)"; null when nothing is lacking. Said from the [Need]s, not
 * from [Fit.reason], which is English.
 */
internal fun fitReason(resources: Resources, fit: Fit): String? {
    val lacks = fit.lacks.filter { it.hard }.takeIf { it.isNotEmpty() } ?: return null
    val window = fit.window
    return resources.joinList(
        lacks.map { need ->
            if (need == Need.CONTEXT && window != null) {
                resources.getString(R.string.chat_fit_lacks_context_window, Fit.tokensLabel(window), Fit.tokensLabel(fit.wanted))
            } else {
                resources.getString(need.lackingRes())
            }
        },
    )
}

@StringRes
private fun Need.lackingRes(): Int = when (this) {
    Need.TOOLS -> R.string.chat_fit_lacks_tools
    Need.VISION -> R.string.chat_fit_lacks_vision
    Need.CONTEXT -> R.string.chat_fit_lacks_context
    Need.REASONING -> R.string.chat_fit_lacks_reasoning
    Need.STRUCTURED_OUTPUT -> R.string.chat_fit_lacks_structured
}

/**
 * The sheet's compact chips for what a model is known to do: its window ("200K"), then Tools, Vision
 * and Reasoning when known yes. A "no" or unknown shows no chip; for this chat the row says more.
 */
internal fun capabilityChips(resources: Resources, caps: ModelCapabilities): List<String> = listOfNotNull(
    caps.contextWindow?.let { Fit.tokensLabel(it) },
    resources.getString(R.string.chat_modelsheet_chip_tools).takeIf { caps.tools == Support.YES },
    resources.getString(R.string.chat_modelsheet_chip_vision).takeIf { caps.vision == Support.YES },
    resources.getString(R.string.chat_modelsheet_chip_reasoning).takeIf { caps.reasoning == Support.YES },
)

/**
 * "Chat only" for a model known not to call tools, where the row doesn't already say it can't be used
 * for that: a chat started on it declares no tools ([dev.locus.core.data.repository.ChatOnlyRule]).
 */
@Composable
internal fun chatOnlyChip(caps: ModelCapabilities, fit: Fit): String? =
    stringResource(R.string.chat_modelsheet_chat_only).takeIf { caps.tools == Support.NO && fit.lacks.none { it.hard } }

/** The picker's line for a model that can't serve this chat: "Can't use here: no vision". */
internal fun cantUseLabel(resources: Resources, fit: Fit): String? =
    fitReason(resources, fit)?.let { resources.getString(R.string.chat_modelsheet_cant_use, it) }

/** The picker's quiet note for needs nobody could confirm: "Vision not confirmed". */
internal fun unconfirmedLabel(resources: Resources, fit: Fit): String? =
    fit.unconfirmed.filter { it.hard }.takeIf { it.isNotEmpty() }
        ?.let { needs -> resources.joinList(needs.map { resources.getString(it.labelRes()) }) }
        ?.replaceFirstChar { it.uppercase() }
        ?.let { resources.getString(R.string.chat_modelsheet_not_confirmed, it) }
