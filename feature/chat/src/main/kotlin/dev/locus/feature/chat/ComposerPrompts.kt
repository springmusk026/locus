package dev.locus.feature.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.prompt.PromptField
import dev.locus.core.agent.prompt.PromptTemplate
import dev.locus.core.agent.prompt.PromptValues
import dev.locus.core.agent.prompt.PromptVariable
import dev.locus.core.data.repository.PromptRepository
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.model.SavedPrompt
import dev.locus.core.ui.PROMPT_CLIPBOARD_KEY
import dev.locus.core.ui.PROMPT_SELECTION_KEY
import dev.locus.core.ui.PromptEditorSheet
import dev.locus.core.ui.PromptFormSheet
import dev.locus.core.ui.PromptPickerSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import javax.inject.Inject

/**
 * What the composer reads when a prompt is picked: only what that prompt uses, and only then
 * (docs/design/saved-prompts.md). The clipboard read is the one Android 12+ announces with a toast.
 */
interface PromptDevice {
    /** The clipboard's text, or null when it's empty or holds no text. */
    fun clipboard(): String?

    /** Today's date and the time now, as the user reads them. */
    fun date(): String
    fun time(): String
}

/** Text for the composer from a picked prompt; [serial] makes each one land once. */
@Immutable
data class PromptInsertion(val text: String, val serial: Long)

/** What a pick was made from: the composer's text then, and the values already read. */
data class PromptPickContext(
    val composerText: String,
    /** Picked from the `/` list: the composer held only the `/` search, which the prompt replaces. */
    val fromSlash: Boolean,
    val clipboard: String = "",
    val date: String = "",
    val time: String = "",
) {
    /** `{{selection}}`: the text shared to Locus, or typed, before the pick. */
    val selection: String get() = if (fromSlash) "" else composerText.trim()
}

/** Which prompt sheet the composer has open. */
sealed interface PromptSheet {
    /** The list to pick from. [composerText] is what the box held when it opened. */
    data class Picker(val composerText: String) : PromptSheet

    /** The blanks of [prompt] to fill before it goes in the box. */
    data class Form(
        val prompt: SavedPrompt,
        val fields: List<PromptField>,
        val askClipboard: Boolean,
        val askSelection: Boolean,
        val context: PromptPickContext,
    ) : PromptSheet

    /** A new prompt, made from the picker; it returns there once saved. */
    data class Editor(val returnTo: Picker, val draft: SavedPrompt? = null, val error: UiText? = null) : PromptSheet
}

/**
 * Saved prompts in the composer, in a chat and on home (docs/design/saved-prompts.md): the `/` list,
 * the picker, the form for a prompt's blanks, and the text it leaves in the box. It never sends:
 * the user reads the text and taps Send. The message is an ordinary user message, so the system
 * prompt and the tool list are untouched.
 */
@HiltViewModel
class ComposerPromptsViewModel @Inject constructor(private val repository: PromptRepository) : ViewModel() {
    val prompts: StateFlow<List<SavedPrompt>> = repository.all
    private val _sheet = MutableStateFlow<PromptSheet?>(null)
    val sheet: StateFlow<PromptSheet?> = _sheet.asStateFlow()
    private val _insertion = MutableStateFlow<PromptInsertion?>(null)
    val insertion: StateFlow<PromptInsertion?> = _insertion.asStateFlow()
    private var serial = 0L

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun openPicker(composerText: String) {
        viewModelScope.launch { repository.refresh() }
        _sheet.value = PromptSheet.Picker(composerText)
    }

    /**
     * [prompt] was picked with [composerText] in the box. Reads only what the prompt uses; puts the
     * text in the box at once, or opens the form when it has blanks or the clipboard or shared text
     * it needs is empty.
     */
    fun pick(prompt: SavedPrompt, composerText: String, fromSlash: Boolean, device: PromptDevice) {
        val template = PromptTemplate.parse(prompt.text)
        val vars = template.variables
        val context = PromptPickContext(
            composerText = composerText,
            fromSlash = fromSlash,
            clipboard = if (PromptVariable.CLIPBOARD in vars) device.clipboard()?.take(MAX_CLIPBOARD).orEmpty() else "",
            date = if (PromptVariable.DATE in vars) device.date() else "",
            time = if (PromptVariable.TIME in vars) device.time() else "",
        )
        val askClipboard = template.usesClipboard && context.clipboard.isBlank()
        val askSelection = template.usesSelection && context.selection.isBlank()
        if (template.fields.isEmpty() && !askClipboard && !askSelection) {
            insert(template, context, emptyMap())
        } else {
            _sheet.value = PromptSheet.Form(prompt, template.fields, askClipboard, askSelection, context)
        }
    }

    /** The form's Insert: [values] by field key, plus the clipboard's and the shared text's boxes when asked. */
    fun fill(values: Map<String, String>) {
        val form = _sheet.value as? PromptSheet.Form ?: return
        insert(PromptTemplate.parse(form.prompt.text), form.context, values)
    }

    private fun insert(template: PromptTemplate, context: PromptPickContext, values: Map<String, String>) {
        val expanded = template.expand(
            PromptValues(
                clipboard = values[PROMPT_CLIPBOARD_KEY] ?: context.clipboard,
                selection = values[PROMPT_SELECTION_KEY] ?: context.selection,
                date = context.date,
                time = context.time,
                fields = values - PROMPT_CLIPBOARD_KEY - PROMPT_SELECTION_KEY,
            ),
        )
        _insertion.value = PromptInsertion(composerTextAfter(expanded, context, template.usesSelection), ++serial)
        _sheet.value = null
    }

    /** The insertion landed in the box. */
    fun consumed(serial: Long) {
        if (_insertion.value?.serial == serial) _insertion.value = null
    }

    fun newPrompt() {
        val picker = _sheet.value as? PromptSheet.Picker ?: PromptSheet.Picker("")
        _sheet.value = PromptSheet.Editor(picker)
    }

    fun save(prompt: SavedPrompt) {
        val editor = _sheet.value as? PromptSheet.Editor ?: return
        viewModelScope.launch {
            try {
                repository.save(prompt)
                if (_sheet.value is PromptSheet.Editor) _sheet.value = editor.returnTo
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = editor.copy(draft = prompt, error = UiText.Res(R.string.chat_prompt_save_failed))
            }
        }
    }

    fun dismiss() {
        _sheet.value = (_sheet.value as? PromptSheet.Editor)?.returnTo
    }

    fun close() {
        _sheet.value = null
    }

    companion object {
        /** Clipboard text beyond this is cut, like selected text shared to Locus. */
        const val MAX_CLIPBOARD = 20_000

        /**
         * What the box holds after a pick. The prompt replaces a `/` search and the text it took as
         * `{{selection}}`; any other text already typed stays, after the prompt and a blank line.
         */
        fun composerTextAfter(expanded: String, context: PromptPickContext, usesSelection: Boolean): String {
            val kept = if (context.fromSlash || usesSelection) "" else context.composerText.trim()
            return if (kept.isEmpty()) expanded else "$expanded\n\n$kept"
        }
    }
}

/** The composer's hooks for saved prompts; null where they aren't offered. */
@Immutable
internal class ComposerPrompts(
    val list: List<SavedPrompt>,
    /** A prompt picked with the box's text then; [fromSlash] when picked from the `/` list. */
    val pick: (prompt: SavedPrompt, composerText: String, fromSlash: Boolean) -> Unit,
    /** Opens the picker with the box's text then. */
    val open: (composerText: String) -> Unit,
    val insertion: PromptInsertion?,
    val consumed: (serial: Long) -> Unit,
)

/** The phone's clipboard, date and time, read when a picked prompt uses them. */
internal class AndroidPromptDevice(private val context: Context) : PromptDevice {
    override fun clipboard(): String? = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        (item.text ?: item.htmlText?.let { androidx.core.text.HtmlCompat.fromHtml(it, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT) })
            ?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // The language Locus shows (the phone's, unless Settings → Language picks another): this is text the
    // user writes, not text for the model.
    private val locale get() = context.resources.configuration.locales[0]

    override fun date(): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(LocalDate.now())

    /** The phone's 12- or 24-hour setting. */
    override fun time(): String = android.text.format.DateFormat.getTimeFormat(context).format(Date())
}

/** The prompt sheets for a composer, and the hooks the composer calls. */
@Composable
internal fun rememberComposerPrompts(viewModel: ComposerPromptsViewModel): ComposerPrompts {
    val list by viewModel.prompts.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val insertion by viewModel.insertion.collectAsStateWithLifecycle()
    val appContext = LocalContext.current
    val device = remember(appContext) { AndroidPromptDevice(appContext) }
    when (val s = sheet) {
        is PromptSheet.Picker -> PromptPickerSheet(
            prompts = list,
            onPick = { p -> viewModel.pick(p, s.composerText, fromSlash = false, device = device) },
            onNew = viewModel::newPrompt,
            onDismiss = viewModel::close,
        )
        is PromptSheet.Form -> PromptFormSheet(
            name = s.prompt.name,
            fields = s.fields,
            askClipboard = s.askClipboard,
            askSelection = s.askSelection,
            onInsert = viewModel::fill,
            onDismiss = viewModel::close,
        )
        is PromptSheet.Editor -> PromptEditorSheet(
            prompt = s.draft,
            error = s.error?.asString(),
            onSave = viewModel::save,
            onDismiss = viewModel::dismiss,
        )
        null -> Unit
    }
    return remember(viewModel, list, insertion, device) {
        ComposerPrompts(
            list = list,
            pick = { p, text, slash -> viewModel.pick(p, text, slash, device) },
            open = viewModel::openPicker,
            insertion = insertion,
            consumed = viewModel::consumed,
        )
    }
}
