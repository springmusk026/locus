package dev.locus.feature.workspace.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.CompactIconButton
import dev.locus.core.designsystem.LocusFeedbackHost
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProvideLocusFeedback
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.TerminalSizes
import dev.locus.core.designsystem.rememberLocusFeedbackState
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.TerminalColors
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.terminal.TabRegistry
import dev.locus.core.terminal.TerminalTab
import dev.locus.feature.workspace.R
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button

/**
 * Interactive terminals in the Linux workspace (docs/design/terminal.md): tabs of login shells
 * running through proot with the agent's isolation, Termux's emulator and view, and an extra-keys
 * row. What's typed here goes to the shell only, never to the model; "Ask Locus about this" puts
 * the selected text in a new chat's composer, unsent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalRoute(
    onBack: () -> Unit,
    /** Opens a new chat with this draft in its composer (not sent). */
    onAsk: (draft: String) -> Unit,
    /** Opens the Workspace's Linux card, where Linux is installed (LINUX_INSTALL_PATH). */
    onInstallLinux: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val fontSp by viewModel.fontSize.collectAsStateWithLifecycle()
    val linux by viewModel.linuxStatus.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // The terminal hosts its own messages, so they sit above its key bar.
    val feedback = rememberLocusFeedbackState()
    val currentFont by rememberUpdatedState(fontSp)

    LaunchedEffect(linux) { viewModel.openIfNone() }
    LaunchedEffect(message) {
        message?.let { feedback.show(it); viewModel.messageShown() }
    }
    // What the sessions report from outside composition (a program's clipboard write), shown here.
    LaunchedEffect(viewModel) {
        viewModel.sessions.notices.collect { feedback.show(it, dev.locus.core.designsystem.Tone.Caution) }
    }

    val client = remember {
        LocusTerminalViewClient(
            onFontStep = { bigger -> viewModel.setFontSize(currentFont + if (bigger) 1 else -1) },
            onCloseFinished = { session -> viewModel.tabs.value.firstOrNull { viewModel.sessions.session(it.id) === session }?.let { viewModel.close(it.id) } },
        )
    }
    val ask: (String?) -> Unit = { text ->
        val draft = TerminalDraft.of(text)
        if (draft != null) onAsk(draft)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedTab = tabs.firstOrNull { it.id == selected }

    ProvideLocusFeedback(feedback) {
    Scaffold(
        snackbarHost = { LocusFeedbackHost(feedback) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                stringResource(R.string.workspace_terminal_title),
                onBack = onBack,
                status = {
                    val where = when {
                        selectedTab == null -> stringResource(R.string.workspace_terminal_status_none)
                        selectedTab.running -> stringResource(R.string.workspace_terminal_status_running, selectedTab.label)
                        else -> stringResource(R.string.workspace_terminal_status_exited, selectedTab.label, selectedTab.exitStatus.toString())
                    }
                    SupportingText(where, style = LocusType.mono, maxLines = 1)
                },
                actions = {
                    if (linux == LinuxStatus.Ready) {
                        IconButton(onClick = viewModel::open, enabled = tabs.size < TabRegistry.MAX_TABS) { Icon(LocusIcons.Add, contentDescription = stringResource(R.string.workspace_terminal_new)) }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(LocusIcons.More, contentDescription = stringResource(R.string.workspace_terminal_actions)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_terminal_ask_selection)) },
                                leadingIcon = { Icon(LocusIcons.Sparkle, contentDescription = null) },
                                enabled = client.selecting,
                                onClick = { menuOpen = false; ask(client.view?.selectedText); client.view?.stopTextSelectionMode() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_terminal_paste)) },
                                enabled = selectedTab?.running == true,
                                onClick = { menuOpen = false; selected?.let { viewModel.sessions.session(it) }?.onPasteTextFromClipboard() },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_terminal_bigger_text)) }, enabled = fontSp < dev.locus.core.terminal.TerminalSessions.MAX_FONT_SP, onClick = { viewModel.setFontSize(fontSp + 1) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_terminal_smaller_text)) }, enabled = fontSp > dev.locus.core.terminal.TerminalSessions.MIN_FONT_SP, onClick = { viewModel.setFontSize(fontSp - 1) })
                            if (selectedTab != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (selectedTab.running) R.string.workspace_terminal_close else R.string.workspace_terminal_close_tab), color = Locus.colors.critical) },
                                    leadingIcon = { Icon(LocusIcons.Close, contentDescription = null, tint = Locus.colors.critical) },
                                    onClick = { menuOpen = false; viewModel.close(selectedTab.id) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding().navigationBarsPadding()) {
            when {
                linux != LinuxStatus.Ready && tabs.isEmpty() -> EmptyState(
                    title = stringResource(if (linux is LinuxStatus.Installing) R.string.workspace_terminal_installing_title else R.string.workspace_terminal_not_installed_title),
                    body = when (val s = linux) {
                        is LinuxStatus.Installing -> stringResource(R.string.workspace_terminal_installing_body, s.step)
                        is LinuxStatus.Unavailable -> s.reason
                        is LinuxStatus.Failed -> stringResource(R.string.workspace_terminal_failed_body, s.message)
                        else -> stringResource(R.string.workspace_terminal_not_installed_body)
                    },
                    icon = LocusIcons.Terminal,
                    action = when (linux) {
                        is LinuxStatus.Unavailable -> null
                        is LinuxStatus.Installing -> ({ Button(onClick = onInstallLinux) { Text(stringResource(R.string.workspace_terminal_see_progress)) } })
                        else -> ({ Button(onClick = onInstallLinux) { Text(stringResource(R.string.workspace_terminal_set_up)) } })
                    },
                )
                tabs.isEmpty() -> EmptyState(
                    title = stringResource(R.string.workspace_terminal_none_title),
                    body = stringResource(R.string.workspace_terminal_none_body),
                    icon = LocusIcons.Terminal,
                    action = { Button(onClick = viewModel::open) { Text(stringResource(R.string.workspace_terminal_open)) } },
                )
                else -> {
                    if (tabs.size > 1) TabStrip(tabs, selected, viewModel::select, viewModel::close)
                    TerminalPane(
                        viewModel = viewModel,
                        sessionId = selected,
                        fontSp = fontSp,
                        client = client,
                        onAsk = ask,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    ExtraKeys(client, onPaste = { selected?.let { viewModel.sessions.session(it) }?.onPasteTextFromClipboard() })
                }
            }
        }
    }
    } // ProvideLocusFeedback
}

@Composable
private fun TabStrip(tabs: List<TerminalTab>, selected: Int?, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.id == selected
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.semantics { this.selected = isSelected; role = Role.Tab },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tab.running) tab.label else stringResource(R.string.workspace_terminal_tab_exited, tab.label),
                        Modifier.clickable { onSelect(tab.id) }.padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm).widthIn(max = TerminalSizes.tabLabelMax),
                        style = LocusType.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CompactIconButton(LocusIcons.Close, contentDescription = stringResource(R.string.workspace_terminal_tab_close, tab.label), onClick = { onClose(tab.id) }, tint = LocalContentColor.current)
                }
            }
        }
    }
}

@Composable
private fun TerminalPane(
    viewModel: TerminalViewModel,
    sessionId: Int?,
    fontSp: Int,
    client: LocusTerminalViewClient,
    onAsk: (String?) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val textPx = with(density) { fontSp.sp.toPx() }.toInt().coerceAtLeast(1)
    val onAskNow by rememberUpdatedState(onAsk)
    val askAboutThis = stringResource(R.string.workspace_terminal_ask_this)
    Box(modifier.background(TerminalColors.backgroundDark)) {
        AndroidView(
            factory = { context ->
                TerminalView(context, null).apply {
                    setTerminalViewClient(client)
                    setTextSize(textPx) // monospace by default
                    tag = textPx
                    isFocusable = true
                    isFocusableInTouchMode = true
                    keepScreenOn = false
                    // Selection's "More…" opens this menu.
                    setOnCreateContextMenuListener { menu, _, _ ->
                        val text = storedSelectedText
                        unsetStoredSelectedText()
                        menu.add(askAboutThis).setOnMenuItemClickListener { onAskNow(text); true }
                    }
                    client.view = this
                }
            },
            update = { view ->
                val session = sessionId?.let { viewModel.sessions.session(it) }
                if (session != null && view.currentSession !== session) {
                    view.attachSession(session)
                    view.requestFocus()
                }
                // setTextSize rebuilds the renderer and resizes the PTY, so only on a real change.
                if (view.tag != textPx) {
                    view.tag = textPx
                    view.setTextSize(textPx)
                }
            },
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xs),
        )
    }
    DisposableEffect(Unit) {
        viewModel.sessions.onScreenUpdated = { session ->
            val view = client.view
            if (view != null && view.currentSession === session) view.onScreenUpdated()
        }
        onDispose {
            viewModel.sessions.onScreenUpdated = null
            client.view = null
        }
    }
}

/** A key on the extra-keys row. */
private sealed class ExtraKey(val label: String, @StringRes val description: Int?) {
    class Code(label: String, @StringRes description: Int, val keyCode: Int) : ExtraKey(label, description)
    /** A character typed as it is; its own symbol is its description. */
    class Chars(val text: String) : ExtraKey(text, null)
    data object Ctrl : ExtraKey("Ctrl", R.string.workspace_terminal_key_ctrl)
    data object Alt : ExtraKey("Alt", R.string.workspace_terminal_key_alt)
}

private val EXTRA_KEYS = listOf(
    ExtraKey.Code("Esc", R.string.workspace_terminal_key_escape, KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Code("Tab", R.string.workspace_terminal_key_tab, KeyEvent.KEYCODE_TAB),
    ExtraKey.Ctrl,
    ExtraKey.Alt,
    ExtraKey.Code("←", R.string.workspace_terminal_key_left, KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Code("↓", R.string.workspace_terminal_key_down, KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Code("↑", R.string.workspace_terminal_key_up, KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Code("→", R.string.workspace_terminal_key_right, KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Chars("|"),
    ExtraKey.Chars("~"),
    ExtraKey.Chars("/"),
    ExtraKey.Chars("-"),
    ExtraKey.Code("Home", R.string.workspace_terminal_key_home, KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Code("End", R.string.workspace_terminal_key_end, KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Code("PgUp", R.string.workspace_terminal_key_page_up, KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Code("PgDn", R.string.workspace_terminal_key_page_down, KeyEvent.KEYCODE_PAGE_DOWN),
)

@Composable
private fun ExtraKeys(client: LocusTerminalViewClient, onPaste: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(TerminalColors.background).horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EXTRA_KEYS.forEach { key ->
            val on = (key is ExtraKey.Ctrl && client.ctrl) || (key is ExtraKey.Alt && client.alt)
            KeyButton(key.label, key.description?.let { stringResource(it) } ?: key.label, on) {
                val view = client.view ?: return@KeyButton
                when (key) {
                    ExtraKey.Ctrl -> client.ctrl = !client.ctrl
                    ExtraKey.Alt -> client.alt = !client.alt
                    // Through the view, so Ctrl/Alt, application cursor keys and the like apply.
                    is ExtraKey.Code -> view.onKeyDown(key.keyCode, KeyEvent(KeyEvent.ACTION_DOWN, key.keyCode))
                    is ExtraKey.Chars -> key.text.codePoints().forEach { view.inputCodePoint(it, false, false) }
                }
            }
        }
        KeyButton(stringResource(R.string.workspace_terminal_paste), stringResource(R.string.workspace_terminal_key_paste), false, onPaste)
    }
}

@Composable
private fun KeyButton(label: String, description: String, on: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = TerminalSizes.keyHeight).widthIn(min = TerminalSizes.keyWidth).semantics { contentDescription = description; if (on) selected = true },
        shape = TerminalSizes.keyShape,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (on) TerminalColors.keyLatched else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (on) TerminalColors.prompt else TerminalColors.output,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = TerminalSizes.keyText, maxLines = 1)
    }
}
