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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.locus.core.agent.integrations.TelegramRemote
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
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
    var note by remember { mutableStateOf<UiText?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }

    LaunchedEffect(Unit) { if (hasKey) actions.savedChatId { id -> if (id != null && chatText.isEmpty()) chatText = id.toString() } }

    val chatId = Telegram.parseChatId(chatText)
    val tokenOk = if (token.isBlank()) hasKey else Telegram.isToken(token)
    fun failed(e: Throwable) { error = e.message?.let { UiText.Plain(it) } ?: UiText.Res(DesignR.string.common_error_generic); note = null; running = null }

    LocusSheet(
        title = integration.displayName(LocalResources.current),
        subtitle = stringResource(R.string.ui_telegram_subtitle),
        onDismiss = onDismiss,
        action = stringResource(DesignR.string.common_save),
        actionEnabled = !busy && tokenOk && chatId != null,
        onAction = {
            running = "save"
            actions.save(token, chatId!!) { r -> r.onSuccess { running = null; onDismiss() }.onFailure(::failed) }
        },
    ) {
        // A word joiner after the slash: "/newbot" is a command and never breaks into "/ newbot".
        Text(stringResource(R.string.ui_telegram_steps), style = LocusType.rowSupporting)
        OutlinedTextField(
            token, { token = it.trim(); error = null },
            label = { Text(if (hasKey) stringResource(R.string.ui_telegram_bot_token) else integration.keyLabelText(LocalResources.current)) },
            supportingText = if (hasKey) { { Text(stringResource(R.string.ui_telegram_token_keep)) } } else null,
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
                            else note = UiText.Res(R.string.ui_telegram_no_messages, d.botUsername?.let { UiText.Plain("@$it") } ?: UiText.Res(R.string.ui_telegram_your_bot))
                        }.onFailure(::failed)
                    }
                },
            ) { BusyLabel(stringResource(R.string.ui_telegram_find_chat), running == "find") }
            TextButton(onClick = { uriHandler.openUri(found?.botUsername?.let { "https://t.me/$it" } ?: integration.signupUrl) }) {
                Text(found?.botUsername?.let { stringResource(R.string.ui_telegram_open_bot, it) } ?: integration.linkLabelText(LocalResources.current))
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
                            stringResource(R.string.ui_telegram_chat_line, chat.name, if (chat.type == "private") stringResource(R.string.ui_telegram_chat_private) else chat.type),
                            style = LocusType.rowSupporting,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            chatText, { chatText = it.trim(); error = null }, label = { Text(stringResource(R.string.ui_telegram_chat_id)) }, singleLine = true,
            isError = chatText.isNotBlank() && chatId == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        val type = found?.chats?.firstOrNull { it.id == chatId }?.type
        if ((type != null && type != "private") || (type == null && chatId != null && chatId < 0)) {
            Text(
                stringResource(R.string.ui_telegram_group_warning),
                style = LocusType.supporting, color = Locus.colors.onCautionContainer,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy && tokenOk && chatId != null,
                onClick = {
                    running = "test"; error = null; note = null
                    actions.test(token, chatId!!) { r -> r.onSuccess { running = null; note = UiText.Res(R.string.ui_telegram_test_sent) }.onFailure(::failed) }
                },
            ) { BusyLabel(stringResource(R.string.ui_telegram_send_test), running == "test") }
            Spacer(Modifier.weight(1f))
            if (hasKey) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text(stringResource(DesignR.string.common_remove), color = Locus.colors.critical) }
        }
        note?.let { SupportingText(it.asString()) }
        error?.let { Text(it.asString(), style = LocusType.supporting, color = Locus.colors.critical) }
        SupportingText(stringResource(R.string.ui_telegram_token_note))
        val remoteFlow = actions.remote
        if (hasKey && remoteFlow != null) TelegramRemoteSection(remoteFlow, chatId, actions)
    }

    if (confirmRemove) {
        ConfirmSheet(
            title = stringResource(R.string.ui_telegram_remove_title),
            message = stringResource(R.string.ui_telegram_remove_message),
            confirmLabel = stringResource(DesignR.string.common_remove),
            tone = Tone.Critical,
            onConfirm = { confirmRemove = false; actions.remove(); onDismiss() },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** How long remote mode stays on before it switches itself off. */
private val REMOTE_DURATIONS = listOf(30L, 60L, 120L, 240L, 480L)

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
    Text(stringResource(R.string.ui_telegram_remote_title), style = LocusType.cardTitle)
    if (!private) {
        SupportingText(stringResource(R.string.ui_telegram_remote_private_only))
        return
    }
    CardGroup {
        SwitchRow(
            stringResource(R.string.ui_telegram_approve_title),
            checked = remote.approvals,
            onCheckedChange = actions.setApprovals,
            subtitle = stringResource(R.string.ui_telegram_approve_subtitle),
        )
        GroupDivider()
        SwitchRow(
            stringResource(R.string.ui_telegram_mode_title),
            checked = on,
            onCheckedChange = { actions.setRemoteMode(it, minutes * 60_000) },
            subtitle = if (on) stringResource(R.string.ui_telegram_mode_on_until, DateFormat.getTimeFormat(context).format(Date(remote.until)))
            else stringResource(R.string.ui_telegram_mode_off_subtitle),
        )
        GroupDivider()
        SwitchRow(
            stringResource(R.string.ui_telegram_widened_title),
            checked = remote.widened,
            onCheckedChange = actions.setWidened,
            subtitle = stringResource(if (remote.widened) R.string.ui_telegram_widened_on else R.string.ui_telegram_widened_off),
        )
    }
    if (!on) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            REMOTE_DURATIONS.forEach { m ->
                val label = if (m < 60) stringResource(R.string.ui_telegram_duration_minutes, m) else stringResource(R.string.ui_telegram_duration_hours, m / 60)
                val spoken = stringResource(R.string.ui_telegram_mode_for, label)
                LocusChip(label, variant = ChipVariant.Filter, selected = minutes == m, onClick = { minutes = m }, contentDescription = spoken)
            }
        }
    }
    SupportingText(stringResource(R.string.ui_telegram_remote_note))
    if (remote.widened) {
        Text(
            stringResource(R.string.ui_telegram_widened_warning),
            style = LocusType.supporting,
            color = Locus.colors.onCautionContainer,
        )
    }
    SupportingText(stringResource(R.string.ui_telegram_untrusted_note))
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
