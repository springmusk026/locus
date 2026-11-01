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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.model.ChatMode
import dev.locus.core.model.Effort
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
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) actions.onAttach(uris) }
    // Voice input through Android's own speech recognizer; the words land in the box for review.
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
            onTextChange(if (text.isBlank()) spoken else "$text $spoken")
        }
    }
    val context = LocalContext.current
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
    )

    val shape = RoundedCornerShape(Radius.sheet)
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            // A soft shadow lifts it off the content scrolling underneath.
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .background(Locus.colors.card, shape)
            .border(0.5.dp, Locus.colors.hairline, shape),
    ) {
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
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = if (imeSend) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                keyboardActions = if (imeSend) KeyboardActions(onSend = { if (canSend) onSend() }) else KeyboardActions.Default,
                // The tag lets instrumentation tests type into the composer (app/src/androidTest).
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs).testTag(COMPOSER_TAG),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            composerPlaceholder(asking, answering, running, controls.planMode, placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        label = shortModelName(controls.model.ifBlank { provider.name }),
                        active = controls.modelOverridden,
                        enabled = controlsEnabled && settings.providers.isNotEmpty(),
                        description = "Model: ${controls.model.ifBlank { provider.name }}",
                        onClickLabel = "Change model",
                        onClick = { onSheet(ControlSheet.Model) },
                        leading = { ProviderBadge(provider.name, provider.baseUrl, provider.onDevice, size = 18.dp, model = controls.model) },
                    )
                    if (provider.supportsEffort) {
                        BarPill(
                            label = controls.effort.label.takeIf { controls.effortOverridden },
                            icon = LocusIcons.Sparkle,
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
                    active = controls.disabledCount > 0,
                    badge = controls.disabledCount.takeIf { it > 0 },
                    state = if (controls.disabledCount > 0) "${controls.disabledCount} switched off" else "All on",
                    onClick = { onSheet(ControlSheet.Tools) },
                )
            }
            Box(Modifier.padding(start = Spacing.xs)) {
                when {
                    running && !canSend -> RoundAction(LocusIcons.Stop, "Stop", MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface, onClick = onStop)
                    !canSend && canListen && enabled -> BarButton(LocusIcons.Mic, "Speak", onClick = {
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
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
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
            onSetPlanMode = actions.setPlanMode,
            onMode = { onSheet(ControlSheet.Mode) },
            onBudget = { onSheet(ControlSheet.Budget) },
            onCompact = actions.onCompact?.let { compact -> { close(); compact() } },
            canCompact = canCompact,
            onDismiss = close,
        )
        null -> Unit
    }
}

/** The + sheet: what goes with the message, and the less frequent switches (tools have their own bar button). */
@Composable
private fun MoreSheet(
    controls: ChatControls,
    onPickFiles: () -> Unit,
    onSetPlanMode: (Boolean) -> Unit,
    onMode: () -> Unit,
    onBudget: () -> Unit,
    onCompact: (() -> Unit)?,
    canCompact: Boolean,
    onDismiss: () -> Unit,
) {
    LocusSheet(title = "Options", onDismiss = onDismiss) {
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            SheetRow(LocusIcons.File, "Photos and files", "Attach from your phone; Locus copies them into the workspace.", onClick = onPickFiles)
        }
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            SheetRow(
                LocusIcons.Note,
                "Plan mode",
                "Look first and propose a plan; nothing changes until you approve.",
                onClick = { onSetPlanMode(!controls.planMode) },
                role = Role.Switch,
            ) { Switch(checked = controls.planMode, onCheckedChange = null) }
            GroupDivider(inset = Spacing.md)
            SheetRow(
                LocusIcons.Shield,
                "Chat mode: ${controls.mode.label}",
                controls.mode.description,
                onClick = onMode,
            ) { Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            GroupDivider(inset = Spacing.md)
            SheetRow(
                LocusIcons.Gauge,
                "Task budget",
                budgetSubtitle(controls),
                onClick = onBudget,
            ) { Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        }
        if (onCompact != null) {
            LocusCard(contentPadding = PaddingValues(0.dp)) {
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

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, size = 36.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
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
) {
    val tint = barTint(active, enabled)
    Box(
        Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        if (live) StatusDot(Tone.Live, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp), size = 6.dp)
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 3.dp).size(15.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badge > 9) "9+" else badge.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
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
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.minimumInteractiveComponentSize().height(36.dp).clip(shape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            }
            .padding(horizontal = if (label == null) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        if (label != null) {
            Text(
                label,
                Modifier.padding(start = 6.dp).widthIn(max = 104.dp),
                style = MaterialTheme.typography.labelLarge,
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
        Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun barTint(active: Boolean, enabled: Boolean): Color = when {
    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    active -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * A model id short enough for the bar: no vendor path or `:tag`, no date stamp
 * (`anthropic/claude-sonnet-5-5-20260301` → `claude-sonnet-5-5`).
 */
internal fun shortModelName(id: String): String {
    val base = id.trim().substringAfterLast('/').substringBefore(':')
    return base.replace(Regex("""[-_@]?(20\d{2}-?\d{2}-?\d{2}|latest)$"""), "").ifBlank { base }
}

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
        Modifier.height(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radius.control)).padding(start = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = a.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(Radius.chip)),
            )
        } else {
            dev.locus.core.designsystem.FileTypeIcon(a.name, isDirectory = false, size = 36.dp)
            Text(a.name, Modifier.padding(start = Spacing.sm).widthIn(max = 140.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarButton(LocusIcons.Close, "Remove ${a.name}", onClick = onRemove)
    }
}

/** The composer's text field, for instrumentation tests (app/src/androidTest). */
const val COMPOSER_TAG = "composer"
