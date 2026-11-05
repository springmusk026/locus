package dev.locus.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import android.text.format.DateFormat
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import dev.locus.core.agent.integrations.TelegramRemote
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.model.TelegramRemoteSettings
import kotlinx.coroutines.flow.Flow
import java.util.Date
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Telegram
import dev.locus.core.data.integrations.TelegramSetup
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.rememberBefore
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone

/** What the Telegram sheet can do. A blank token means the one already saved. */
class TelegramActions(
    val savedChatId: (onResult: (Long?) -> Unit) -> Unit,
    val findChats: (token: String, onResult: (Result<Telegram.Discovery>) -> Unit) -> Unit,
    val test: (token: String, chatId: Long, onResult: (Result<Unit>) -> Unit) -> Unit,
    val save: (token: String, chatId: Long, onResult: (Result<Unit>) -> Unit) -> Unit,
    val remove: () -> Unit,
    /** The remote channel's settings (docs/design/telegram-remote.md); null hides the Remote section. */
    val remote: Flow<TelegramRemoteSettings>? = null,
    val setApprovals: (Boolean) -> Unit = {},
    val setRemoteMode: (on: Boolean, durationMs: Long) -> Unit = { _, _ -> },
    val setWidened: (Boolean) -> Unit = {},
) {
    companion object {
        val None = TelegramActions({}, { _, _ -> }, { _, _, _ -> }, { _, _, _ -> }, {})

        /** The sheet's actions over [setup], run in [scope] (a view model's). Failures come back as results. */
        fun of(setup: TelegramSetup, scope: CoroutineScope) = TelegramActions(
            savedChatId = { done -> scope.launch { done(runCatching { setup.savedChatId() }.getOrNull()) } },
            findChats = { token, done -> scope.launch { done(runCatching { setup.findChats(token) }) } },
            test = { token, chatId, done -> scope.launch { done(runCatching { setup.sendTest(token, chatId) }) } },
            save = { token, chatId, done -> scope.launch { done(runCatching { setup.save(token, chatId) }) } },
            remove = { scope.launch { setup.remove() } },
            remote = setup.remote,
            setApprovals = { on -> scope.launch { setup.setRemoteApprovals(on) } },
            setRemoteMode = { on, ms -> scope.launch { setup.setRemoteMode(on, ms) } },
            setWidened = { on -> scope.launch { setup.setRemoteWidened(on) } },
        )
    }
}

/**
 * Telegram setup, shown from Settings → Integrations and from onboarding: paste the bot token, message the bot, then "Find my chat" reads the bot's
 * pending messages once (getUpdates) to fill in the chat id. Nothing keeps polling afterwards.
 */
@Composable
fun TelegramSheet(integration: Integration, hasKey: Boolean, actions: TelegramActions, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var token by remember { mutableStateOf("") }
    var chatText by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Telegram.Discovery?>(null) }
    /** The action running now ("find", "test" or "save"), or null. */
    var running by remember { mutableStateOf<String?>(null) }
    val busy = running != null
    var confirmRemove by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (hasKey) actions.savedChatId { id -> if (id != null && chatText.isEmpty()) chatText = id.toString() } }

    val chatId = Telegram.parseChatId(chatText)
    val tokenOk = if (token.isBlank()) hasKey else Telegram.isToken(token)
    fun failed(e: Throwable) { error = e.message ?: "Something went wrong."; note = null; running = null }

    LocusSheet(
        title = integration.name,
        subtitle = "Notifications and scheduled task results from your own bot.",
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = !busy && tokenOk && chatId != null,
        onAction = {
            running = "save"
            actions.save(token, chatId!!) { r -> r.onSuccess { running = null; onDismiss() }.onFailure(::failed) }
        },
    ) {
        // A word joiner after the slash: "/newbot" is a command and never breaks into "/ newbot".
        Text(
            "1. In Telegram, open @BotFather, send /\u2060newbot and copy the token it gives you.\n" +
                "2. Open your new bot and press Start (or send it any message).\n" +
                "3. Paste the token here and tap Find my chat.",
            style = LocusType.rowSupporting,
        )
        OutlinedTextField(
            token, { token = it.trim(); error = null },
            label = { Text(if (hasKey) "Bot token" else integration.keyLabel) },
            supportingText = if (hasKey) { { Text("Leave blank to keep the saved one") } } else null,
            singleLine = true, isError = token.isNotBlank() && !Telegram.isToken(token),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        // Wraps at a large font rather than breaking "@BotFather" mid-name.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(
                enabled = !busy && tokenOk,
                onClick = {
                    running = "find"; error = null; note = null
                    actions.findChats(token) { r ->
                        r.onSuccess { d ->
                            running = null
                            found = d
                            val first = d.chats.firstOrNull()
                            if (first != null) chatText = first.id.toString()
                            else note = "No messages yet. Open " + (d.botUsername?.let { "@$it" } ?: "your bot") + " in Telegram, press Start, then tap Find my chat again."
                        }.onFailure(::failed)
                    }
                },
            ) { BusyLabel("Find my chat", running == "find") }
            TextButton(onClick = { uriHandler.openUri(found?.botUsername?.let { "https://t.me/$it" } ?: integration.signupUrl) }) {
                Text(found?.botUsername?.let { "Open @$it" } ?: integration.linkLabel)
            }
        }
        found?.chats?.takeIf { it.isNotEmpty() }?.let { chats ->
            Column {
                chats.forEach { chat ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chatText = chat.id.toString() }.padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chatId == chat.id, onClick = { chatText = chat.id.toString() })
                        Text(
                            "${chat.name} · ${if (chat.type == "private") "private chat" else chat.type}",
                            style = LocusType.rowSupporting,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            chatText, { chatText = it.trim(); error = null }, label = { Text("Chat ID") }, singleLine = true,
            isError = chatText.isNotBlank() && chatId == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        val type = found?.chats?.firstOrNull { it.id == chatId }?.type
        if ((type != null && type != "private") || (type == null && chatId != null && chatId < 0)) {
            Text(
                "This is a group or channel: everyone in it will see your notifications.",
                style = LocusType.supporting, color = Locus.colors.onCautionContainer,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy && tokenOk && chatId != null,
                onClick = {
                    running = "test"; error = null; note = null
                    actions.test(token, chatId!!) { r -> r.onSuccess { running = null; note = "Sent. Check Telegram." }.onFailure(::failed) }
                },
            ) { BusyLabel("Send a test", running == "test") }
            Spacer(Modifier.weight(1f))
            if (hasKey) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("Remove", color = Locus.colors.critical) }
        }
        note?.let { SupportingText(it) }
        error?.let { Text(it, style = LocusType.supporting, color = Locus.colors.critical) }
        SupportingText(
            "The token is stored encrypted on this phone and the AI never sees it. Locus only calls Telegram to send you something; " +
                "it reads what you send the bot only once here, to find your chat, and while the remote options below are on.",
        )
        val remoteFlow = actions.remote
        if (hasKey && remoteFlow != null) TelegramRemoteSection(remoteFlow, chatId, actions)
    }

    if (confirmRemove) {
        ConfirmSheet(
            title = "Remove Telegram?",
            message = "The bot token and chat are deleted from this phone. Notifications and scheduled task results stop going to Telegram.",
            confirmLabel = "Remove",
            tone = Tone.Critical,
            onConfirm = { confirmRemove = false; actions.remove(); onDismiss() },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** How long remote mode stays on before it switches itself off. */
private val REMOTE_DURATIONS = listOf(30L to "30 min", 60L to "1 h", 120L to "2 h", 240L to "4 h", 480L to "8 h")

/**
 * Remote: approvals by button, and remote mode (docs/design/telegram-remote.md). Both need a private chat
 * with the bot, so a button press or message can only come from the user's own account.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun TelegramRemoteSection(remoteFlow: Flow<TelegramRemoteSettings>, chatId: Long?, actions: TelegramActions) {
    val remote by remoteFlow.collectAsState(initial = TelegramRemoteSettings())
    val context = LocalContext.current
    var minutes by remember { mutableStateOf(60L) }
    val private = chatId != null && TelegramRemote.privateChat(chatId)
    // Ticks: "On until 14:30" turns back into the off state at 14:30, even if nothing else changes.
    val on by rememberBefore(remote.switchesOffAt)

    GroupDivider(inset = 0.dp)
    Text("Remote", style = LocusType.cardTitle)
    if (!private) {
        SupportingText("Approving and sending tasks from Telegram need a private chat with your bot, so only your account can press a button.")
        return
    }
    CardGroup {
        SwitchRow(
            "Approve from Telegram",
            checked = remote.approvals,
            onCheckedChange = actions.setApprovals,
            subtitle = "Allow and Deny buttons come to this chat when a task working in the background asks.",
        )
        GroupDivider()
        SwitchRow(
            "Remote mode",
            checked = on,
            onCheckedChange = { actions.setRemoteMode(it, minutes * 60_000) },
            subtitle = if (on) "On until ${DateFormat.getTimeFormat(context).format(Date(remote.until))}. Messages to the bot start tasks in a chat called Telegram, or join the one running."
            else "Messages to the bot start tasks in a chat called Telegram. It switches itself off after the time below.",
        )
        GroupDivider()
        SwitchRow(
            "Let Telegram tasks do more than read",
            checked = remote.widened,
            onCheckedChange = actions.setWidened,
            subtitle = if (remote.widened) "Telegram tasks run like a chat, asking before they change anything."
            else "Off: they can look things up, read and answer, but can't change files, run commands or send anything.",
        )
    }
    if (!on) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            REMOTE_DURATIONS.forEach { (m, label) ->
                LocusChip(label, variant = ChipVariant.Filter, selected = minutes == m, onClick = { minutes = m }, contentDescription = "Remote mode for $label")
            }
        }
    }
    SupportingText(
        "Anything the safety reviewer flagged, or that must be confirmed (payments, sending, after reading outside content), still needs a tap " +
            "in Locus; Telegram only says so. While remote mode is on, a notification shows and Locus keeps a connection to Telegram open, " +
            "which uses some battery. Send /off to the bot to stop it from anywhere.",
    )
    if (remote.widened) {
        Text(
            "Anyone who gets into your Telegram account can ask too, and plain approvals can be answered from the same chat. " +
                "A bot chat isn't proof it's you, so off is the safe default.",
            style = LocusType.supporting,
            color = Locus.colors.onCautionContainer,
        )
    }
    SupportingText(
        "Messages from Telegram are treated as outside content, never as your own words, and only this chat counts. " +
            "Locus ignores messages sent before remote mode was on, and more than a few a minute.",
    )
}

/** A button label with a small spinner in front while its action runs. */
@Composable
private fun BusyLabel(text: String, busy: Boolean) {
    if (busy) {
        InlineSpinner(size = IconSize.small)
        Spacer(Modifier.size(Spacing.sm))
    }
    Text(text)
}

/**
 * Each built-in integration with whether it's connected. Most count once their key is saved ([keys]);
 * Telegram only when [telegramConnected] (see TelegramSetup.connected), so a value that can't deliver
 * anything never shows as Connected.
 */
fun integrationRows(keys: Set<String>, telegramConnected: Boolean): List<Pair<Integration, Boolean>> =
    Integrations.all.map { it to if (it.id == Integrations.telegram.id) telegramConnected else it.secret in keys }
