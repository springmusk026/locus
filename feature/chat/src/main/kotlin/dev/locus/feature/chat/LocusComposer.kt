package dev.locus.feature.chat

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ComposerSizes
import dev.locus.core.designsystem.CountBadge
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.ChatGlyphs
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.container
import dev.locus.core.designsystem.floatingSurface
import dev.locus.core.model.ChatMode
import dev.locus.core.model.Effort
import dev.locus.core.model.ModelNames
import dev.locus.core.model.Part

/** What the composer's controls show: the model, its per-chat settings, and what the sheets list. */
@Immutable
internal data class ComposerSettings(
    val provider: ChatProvider? = null,
    val providers: List<ChatProvider> = emptyList(),
    val controls: ChatControls = ChatControls(),
    val connectors: List<ChatConnector> = emptyList(),
    val picker: ModelPickerData = ModelPickerData(),
    /** The running task's spend toward its budget, for the budget sheet; null when no task runs with one. */
    val taskMeter: dev.locus.core.agent.BudgetMeter? = null,
)

/** What the composer can change. Shared by a chat (applies to it) and home (applies to the chat it starts). */
internal class ComposerActions(
    val setModel: (providerId: String, model: String?) -> Unit = { _, _ -> },
    /** The model sheet opened: fetch what it lists. */
    val prepareModels: () -> Unit = {},
    val toggleFavorite: (dev.locus.core.model.ModelRef) -> Unit = {},
    val enableCatalog: () -> Unit = {},
    val setEffort: (Effort?) -> Unit = {},
    val setPlanMode: (Boolean) -> Unit = {},
    val setMode: (ChatMode) -> Unit = {},
    val setToolEnabled: (key: String, enabled: Boolean) -> Unit = { _, _ -> },
    /** The chat's own task budget; null for the default from Settings. */
    val setBudget: (dev.locus.core.model.TaskBudget?) -> Unit = {},
    val onAttach: (List<Uri>) -> Unit = {},
    val onRemoveAttachment: (Part.Attachment) -> Unit = {},
    /** Summarise the conversation now; null where there's no conversation yet (home). */
    val onCompact: (() -> Unit)? = null,
    /** Pick a saved workflow to run here; null where workflows aren't offered (home). */
    val onWorkflows: (() -> Unit)? = null,
    /** Open voice mode (docs/design/voice-mode.md): the mic's long-press and the + sheet; null where it isn't offered. */
    val onVoiceMode: (() -> Unit)? = null,
)

/** A live panel's toggle at the composer's top right (in a chat): tinted while open, a live dot while its tool runs. */
internal class ComposerPanel(val icon: ImageVector, val label: String, val open: Boolean, val live: Boolean, val onToggle: () -> Unit)

/**
 * The one input bar, in a chat and on home. A text field on top and a single slim row under it:
 * `+` (attach, plan mode, chat mode, task budget, summarise), then the chat mode and plan while they
 * differ from the default, the model as logo + short name, thinking and tools; send / stop / mic on
 * the right. Every control is monochrome until it differs from the default (brand tint), and each
 * is a 48 dp target. Sheets open from here ([sheet] is hoisted so the chat header's budget meter can
 * open the budget sheet too).
 */
@Composable
internal fun LocusComposer(
    text: String,
    onTextChange: (String) -> Unit,
    settings: ComposerSettings,
    actions: ComposerActions,
    sheet: ControlSheet?,
    onSheet: (ControlSheet?) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<Part.Attachment> = emptyList(),
    running: Boolean = false,
    enabled: Boolean = true,
    /** The agent waits on a question: Send answers it. Otherwise, while it runs, Send queues (steer and queue). */
    answering: Boolean = false,
    onStop: () -> Unit = {},
    canCompact: Boolean = false,
    placeholder: String = "Message Locus",
    /** Send from the keyboard's action key (home); in a chat Enter makes a new line. */
    imeSend: Boolean = false,
    /** The Aside switch is on: Send asks a side question (as `/btw`) instead of messaging the task. */
    aside: Boolean = false,
    /** Turns the Aside switch on or off; null where there are no side questions (home). */
    onAsideChange: ((Boolean) -> Unit)? = null,
    /** The chat's live panel toggles; none on home. */
    panels: List<ComposerPanel> = emptyList(),
    /** Each new value above 0 puts the cursor in the box with the keyboard up (the quick settings tile). */
    focusKey: Int = 0,
    /** Each new value above 0 starts dictation into the box (the Voice shortcut). */
    listenKey: Int = 0,
    /** A slim row docked into the composer's top edge (the task's checklist), above the text box; null for none. */
    dock: (@Composable () -> Unit)? = null,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) actions.onAttach(uris) }
    // The system Photo Picker: no storage permission, and only what the user picks is shared
    // (developer.android.com/training/data-storage/shared/photo-picker).
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris -> if (uris.isNotEmpty()) actions.onAttach(uris) }
    // The camera app writes the photo to a file Locus offers through its FileProvider; it is then
    // attached like any picked file (copied into inbox/). No CAMERA permission: Locus doesn't declare it.
    val context = LocalContext.current
    var shot by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = shot?.let(Uri::parse)
        shot = null
        if (saved && uri != null) actions.onAttach(listOf(uri))
    }
    val takePhoto: () -> Unit = {
        runCatching {
            val uri = CameraCapture.newPhotoUri(context)
            shot = uri.toString()
            camera.launch(uri)
        }
    }
    // Voice input through Android's own speech recognizer; the words land in the box for review.
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
            onTextChange(if (text.isBlank()) spoken else "$text $spoken")
        }
    }
    val canListen = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    // A side question: the switch is on, or the text is the `/btw` command.
    val asking = onAsideChange != null && (aside || SideCommand.parse(text) != null)
    // While the agent works, a typed message is queued for its next step (or answers its open question).
    // A side question needs words; attachments never go with it.
    val canSend = enabled && if (running || asking) text.isNotBlank() else text.isNotBlank() || attachments.isNotEmpty()
    val controlsEnabled = enabled && !running
    val controls = settings.controls
    val provider = settings.provider

    ComposerSheets(
        sheet = sheet,
        onSheet = onSheet,
        settings = settings,
        actions = actions,
        canCompact = canCompact,
        onPickFiles = { pick.launch(arrayOf("*/*")) },
        onPickPhotos = { photos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        onTakePhoto = takePhoto,
    )
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Each key is acted on once, even when the composer comes back on screen later with the same key.
    var focused by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var listened by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(focusKey) {
        if (focusKey > focused) {
            focused = focusKey
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    androidx.compose.runtime.LaunchedEffect(listenKey) {
        if (listenKey > listened && canListen) {
            listened = listenKey
            runCatching {
                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            }
        }
    }

    Column(
        // A soft shadow lifts it off the content scrolling underneath.
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm).floatingSurface(),
    ) {
        if (dock != null) {
            dock()
            dev.locus.core.designsystem.GroupDivider(inset = Spacing.lg)
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = Spacing.md, end = Spacing.md, top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) { attachments.forEach { a -> ComposerAttachment(a, onRemove = { actions.onRemoveAttachment(a) }) } }
        }
        // The panel toggles sit beside the field, so it never runs under them. They give way while there is
        // text (the field needs the room) and come back once it's empty.
        Row(verticalAlignment = Alignment.Top) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                maxLines = 6,
                textStyle = LocusType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = if (imeSend) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                keyboardActions = if (imeSend) KeyboardActions(onSend = { if (canSend) onSend() }) else KeyboardActions.Default,
                // The tag lets instrumentation tests type into the composer (app/src/androidTest).
                modifier = Modifier.weight(1f).heightIn(min = ComposerSizes.field).padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs).testTag(COMPOSER_TAG)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        SupportingText(composerPlaceholder(asking, answering, running, controls.planMode, placeholder), style = LocusType.body)
                    }
                    inner()
                },
            )
            AnimatedVisibility(
                visible = panels.isNotEmpty() && text.isEmpty(),
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Row(Modifier.padding(end = Spacing.sm)) {
                    panels.forEach { p ->
                        BarButton(p.icon, p.label, onClick = p.onToggle, active = p.open, state = if (p.open) "Open" else "Closed", live = p.live)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton(LocusIcons.Add, "More options", enabled = controlsEnabled, onClick = { onSheet(ControlSheet.More) })
                // Side questions (`/btw`) without typing the command: offered while the task runs, and
                // shown while on. Unlike the other controls it works mid-task, since it never touches the task.
                if (onAsideChange != null && (running || aside)) {
                    BarPill(
                        label = "Aside",
                        icon = LocusIcons.Chats,
                        active = aside,
                        enabled = enabled,
                        description = "Side question: answered apart, the task keeps going",
                        state = if (aside) "On" else "Off",
                        onClickLabel = if (aside) "Send to the task instead" else "Ask a side question",
                        onClick = { onAsideChange(!aside) },
                    )
                }
                // Chat mode and plan mode are set in the + sheet; first in the bar, so they stay in view on
                // narrow phones, while they differ from the default. Each opens where it's changed.
                if (controls.mode != ChatMode.FULL) {
                    BarPill(
                        label = controls.mode.label,
                        icon = LocusIcons.Shield,
                        active = true,
                        enabled = controlsEnabled,
                        description = "Chat mode",
                        state = controls.mode.label,
                        onClickLabel = "Change chat mode",
                        onClick = { onSheet(ControlSheet.Mode) },
                    )
                }
                if (controls.planMode) {
                    BarPill(
                        label = "Plan",
                        icon = LocusIcons.Note,
                        active = true,
                        enabled = controlsEnabled,
                        description = "Plan mode",
                        state = "On",
                        onClickLabel = "Change plan mode",
                        onClick = { onSheet(ControlSheet.More) },
                    )
                }
                if (provider != null) {
                    BarPill(
                        // While a task runs the Aside pill and Stop take the row: the model shows as its badge
                        // alone (named for TalkBack) rather than a name cut off at the edge.
                        label = composerModelName(controls, provider).takeUnless { running },
                        active = controls.modelOverridden,
                        enabled = controlsEnabled && settings.providers.isNotEmpty(),
                        description = "Model: ${composerModelName(controls, provider)}",
                        onClickLabel = "Change model",
                        onClick = { onSheet(ControlSheet.Model) },
                        leading = { ProviderBadge(provider.name, provider.baseUrl, provider.onDevice, size = IconSize.medium, model = controls.model) },
                    )
                    if (provider.supportsEffort) {
                        BarPill(
                            label = controls.effort.label.takeIf { controls.effortOverridden },
                            icon = ChatGlyphs.Thought,
                            active = controls.effortOverridden,
                            enabled = controlsEnabled,
                            description = "Thinking: ${controls.effort.label}",
                            onClickLabel = "Change thinking effort",
                            onClick = { onSheet(ControlSheet.Effort) },
                        )
                    }
                }
                BarButton(
                    LocusIcons.Tool,
                    "Tools",
                    enabled = controlsEnabled,
                    active = controls.chatOnly || controls.disabledCount > 0,
                    badge = controls.disabledCount.takeIf { it > 0 && !controls.chatOnly },
                    state = when {
                        controls.chatOnly -> "None in this chat"
                        controls.disabledCount > 0 -> "${controls.disabledCount} switched off"
                        else -> "All on"
                    },
                    onClick = { onSheet(ControlSheet.Tools) },
                )
            }
            Box(Modifier.padding(start = Spacing.xs)) {
                when {
                    running && !canSend -> RoundAction(LocusIcons.Stop, "Stop", MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface, onClick = onStop)
                    !canSend && canListen && enabled -> BarButton(LocusIcons.Mic, "Speak", onLongClick = actions.onVoiceMode, longClickLabel = "Voice mode", onClick = {
                        runCatching {
                            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                        }
                    })
                    else -> RoundAction(
                        LocusIcons.Send,
                        when {
                            asking -> "Ask side question: answered apart, not added to the chat"
                            running && !answering -> "Tell Locus: read after the current step, without stopping the task"
                            else -> "Send"
                        },
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill),
                        if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.disabled),
                        enabled = canSend,
                        onClick = onSend,
                    )
                }
            }
        }
    }
}

/**
 * What the empty text box says. While the agent works, what's typed goes into the running task at its
 * next step (correct it, add something, change priorities, ask it to wrap up), so it says that.
 */
internal fun composerPlaceholder(asking: Boolean, answering: Boolean, running: Boolean, planMode: Boolean, idle: String): String = when {
    asking -> "Ask a side question…"
    answering -> "Type your answer…"
    running -> "Tell Locus while it works…"
    planMode -> "Describe the task; Locus plans first"
    else -> idle
}

/** The composer's sheets: model, thinking, tools, and the + sheet. */
@Composable
private fun ComposerSheets(
    sheet: ControlSheet?,
    onSheet: (ControlSheet?) -> Unit,
    settings: ComposerSettings,
    actions: ComposerActions,
    canCompact: Boolean,
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val close = { onSheet(null) }
    when (sheet) {
        ControlSheet.Model -> ModelSheet(
            settings,
            onPick = { id, model -> close(); actions.setModel(id, model) },
            onOpen = actions.prepareModels,
            onToggleFavorite = actions.toggleFavorite,
            onEnableCatalog = actions.enableCatalog,
            onDismiss = close,
        )
        ControlSheet.Effort -> EffortSheet(settings.provider, settings.controls, onPick = { close(); actions.setEffort(it) }, onDismiss = close)
        ControlSheet.Mode -> ModeSheet(settings.controls, onPick = { close(); actions.setMode(it) }, onDismiss = close)
        ControlSheet.Tools -> ToolsSheet(settings.controls, settings.connectors, onSetEnabled = actions.setToolEnabled, onDismiss = close)
        ControlSheet.Budget -> BudgetSheet(settings.controls, onSet = actions.setBudget, onDismiss = close, meter = settings.taskMeter)
        ControlSheet.More -> MoreSheet(
            settings.controls,
            onPickFiles = { close(); onPickFiles() },
            onPickPhotos = { close(); onPickPhotos() },
            onTakePhoto = { close(); onTakePhoto() },
            onSetPlanMode = actions.setPlanMode,
            onMode = { onSheet(ControlSheet.Mode) },
            onBudget = { onSheet(ControlSheet.Budget) },
            onCompact = actions.onCompact?.let { compact -> { close(); compact() } },
            canCompact = canCompact,
            onDismiss = close,
            onWorkflows = actions.onWorkflows?.let { open -> { close(); open() } },
            onVoiceMode = actions.onVoiceMode?.let { open -> { close(); open() } },
        )
        null -> Unit
    }
}

/** The + sheet: what goes with the message, and the less frequent switches (tools have their own bar button). */
@Composable
private fun MoreSheet(
    controls: ChatControls,
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onTakePhoto: () -> Unit,
    onSetPlanMode: (Boolean) -> Unit,
    onMode: () -> Unit,
    onBudget: () -> Unit,
    onCompact: (() -> Unit)?,
    canCompact: Boolean,
    onDismiss: () -> Unit,
    onWorkflows: (() -> Unit)? = null,
    onVoiceMode: (() -> Unit)? = null,
) {
    LocusSheet(title = "Options", onDismiss = onDismiss) {
        if (onVoiceMode != null) {
            CardGroup {
                SheetRow(LocusIcons.Mic, "Voice mode", "Talk with Locus hands-free; it reads replies aloud. Also: hold the mic.", onClick = onVoiceMode)
            }
        }
        CardGroup {
            SheetRow(LocusIcons.Image, "Photos", "Pick photos or videos; only the ones you choose are shared.", onClick = onPickPhotos)
            GroupDivider(inset = Insets.iconRow)
            SheetRow(LocusIcons.Camera, "Camera", "Take a photo with your camera app.", onClick = onTakePhoto)
            GroupDivider(inset = Insets.iconRow)
            SheetRow(LocusIcons.File, "Files", "Attach any file; Locus copies it into the workspace.", onClick = onPickFiles)
            if (onWorkflows != null) {
                GroupDivider(inset = Insets.iconRow)
                SheetRow(LocusIcons.Play, "Run a workflow", "Start a task you saved, with new inputs.", onClick = onWorkflows)
            }
        }
        CardGroup {
            SwitchRow(
                title = "Plan mode",
                checked = controls.planMode,
                onCheckedChange = { onSetPlanMode(it) },
                subtitle = "Look first and propose a plan; nothing changes until you approve.",
                icon = LocusIcons.Note,
            )
            GroupDivider(inset = Insets.iconRow)
            SheetRow(
                LocusIcons.Shield,
                "Chat mode: ${controls.mode.label}",
                controls.mode.description,
                onClick = onMode,
            ) { RowChevron() }
            GroupDivider(inset = Insets.iconRow)
            SheetRow(
                LocusIcons.Gauge,
                "Task budget",
                budgetSubtitle(controls),
                onClick = onBudget,
            ) { RowChevron() }
        }
        if (onCompact != null) {
            CardGroup {
                SheetRow(
                    LocusIcons.Compact,
                    "Summarise the conversation",
                    if (canCompact) "Fold older messages into a summary to free up context." else "Available once the chat is longer and nothing is running.",
                    enabled = canCompact,
                    onClick = onCompact,
                )
            }
        }
        SectionNote("Model, thinking and tools are one tap away in the bar below the text box.")
    }
}

/** One action in the + sheet: a [ListRow] with its icon on a tile; [enabled] false fades it and TalkBack says it's disabled. */
@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        // Read whole: each is one short sentence, and at a large font two lines cut it.
        subtitleMaxLines = Int.MAX_VALUE,
        onClick = onClick,
        enabled = enabled,
        leading = { IconTile(icon) },
        trailing = trailing,
    )
}

/** A 36 dp icon in a 48 dp target: monochrome, brand when [active], with an optional count badge. */
@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    badge: Int? = null,
    state: String? = null,
    /** A live dot: its tool is running now. */
    live: Boolean = false,
    /** A second action on long-press (the mic opens voice mode), announced as [longClickLabel]. */
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val tint = barTint(active, enabled)
    Box(
        Modifier.minimumInteractiveComponentSize().size(ComposerSizes.control).clip(CircleShape)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize.medium))
        if (live) {
            StatusDot(Tone.Live, Modifier.align(Alignment.TopEnd).offset(x = -(Spacing.xs + Spacing.xxs), y = Spacing.xs + Spacing.xxs), size = ComposerSizes.liveDot)
        }
        if (badge != null) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = -Spacing.xxs, y = Spacing.xxs))
    }
}

/** A 36 dp control in a 48 dp target that can carry a short label: the model, thinking when changed, plan when on. */
@Composable
private fun BarPill(
    label: String?,
    active: Boolean,
    enabled: Boolean,
    description: String,
    onClickLabel: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    state: String? = null,
) {
    val tint = barTint(active, enabled)
    Row(
        // At least the bar's height; taller with a large font, so a 200% label isn't clipped top and bottom.
        Modifier.minimumInteractiveComponentSize().heightIn(min = ComposerSizes.control).clip(Radius.pill)
            .background(if (active) Tone.Brand.container() else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            }
            .padding(horizontal = if (label == null) Spacing.sm else Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize.medium))
        if (label != null) {
            Text(
                label,
                Modifier.padding(start = Spacing.xs).widthIn(max = ComposerSizes.pillLabelMax),
                style = LocusType.label,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Send and stop: a filled 36 dp circle in a 48 dp target. */
@Composable
private fun RoundAction(icon: ImageVector, description: String, container: Color, content: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.minimumInteractiveComponentSize().size(ComposerSizes.control).clip(CircleShape).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(IconSize.medium)) }
}

@Composable
private fun barTint(active: Boolean, enabled: Boolean): Color = when {
    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.disabled)
    active -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The model pill's name: an on-device model's name from its list, as the model sheet shows it
 * ([ChatControls.modelName]), else [ModelNames.short] of the model id, or the provider's name with no model.
 */
internal fun composerModelName(controls: ChatControls, provider: ChatProvider): String =
    controls.modelName ?: ModelNames.short(controls.model.ifBlank { provider.name })

/** An image attachment's thumbnail, decoded small off the main thread; null for other files or until decoded. */
@Composable
internal fun rememberThumbnail(a: Part.Attachment): androidx.compose.ui.graphics.ImageBitmap? =
    androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.path) {
        if (a.mimeType.startsWith("image/")) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                runCatching { android.graphics.BitmapFactory.decodeFile(a.path, opts)?.asImageBitmap() }.getOrNull()
            }
        }
    }.value

/** A picked attachment waiting to be sent: a small thumbnail or the file's name, with remove. */
@Composable
private fun ComposerAttachment(a: Part.Attachment, onRemove: () -> Unit) {
    val thumb = rememberThumbnail(a)
    Row(
        Modifier.height(Sizes.touchTarget).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(start = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = a.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(ComposerSizes.thumbnail).clip(MaterialTheme.shapes.small),
            )
        } else {
            dev.locus.core.designsystem.FileTypeIcon(a.name, isDirectory = false, size = IconSize.tile)
            Text(a.name, Modifier.padding(start = Spacing.sm).widthIn(max = ComposerSizes.attachmentName), style = LocusType.chip, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarButton(LocusIcons.Close, "Remove ${a.name}", onClick = onRemove)
    }
}

/** The composer's text field, for instrumentation tests (app/src/androidTest). */
const val COMPOSER_TAG = "composer"

/** Most photos picked at once from the + sheet. */
private const val MAX_PHOTOS = 10

/** Where the camera app saves a photo before it is attached: cache/camera/, offered through the exports FileProvider. */
internal object CameraCapture {
    fun newPhotoUri(context: android.content.Context): Uri {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        // Old shots were copied into inbox/ when attached; clear them so the cache doesn't grow.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }
}
