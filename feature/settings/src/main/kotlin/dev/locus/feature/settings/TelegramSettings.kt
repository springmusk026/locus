package dev.locus.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Telegram
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusSheet

/** What the Telegram sheet can do. A blank token means the one already saved. */
internal class TelegramActions(
    val savedChatId: (onResult: (Long?) -> Unit) -> Unit,
    val findChats: (token: String, onResult: (Result<Telegram.Discovery>) -> Unit) -> Unit,
    val test: (token: String, chatId: Long, onResult: (Result<Unit>) -> Unit) -> Unit,
    val save: (token: String, chatId: Long, onResult: (Result<Unit>) -> Unit) -> Unit,
    val remove: () -> Unit,
) {
    companion object {
        val None = TelegramActions({}, { _, _ -> }, { _, _, _ -> }, { _, _, _ -> }, {})
    }
}

/**
 * Telegram setup: paste the bot token, message the bot, then "Find my chat" reads the bot's
 * pending messages once (getUpdates) to fill in the chat id. Nothing keeps polling afterwards.
 */
@Composable
internal fun TelegramSheet(integration: Integration, hasKey: Boolean, actions: TelegramActions, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var token by remember { mutableStateOf("") }
    var chatText by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Telegram.Discovery?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (hasKey) actions.savedChatId { id -> if (id != null && chatText.isEmpty()) chatText = id.toString() } }

    val chatId = Telegram.parseChatId(chatText)
    val tokenOk = if (token.isBlank()) hasKey else Telegram.isToken(token)
    fun failed(e: Throwable) { error = e.message ?: "Something went wrong."; note = null; busy = false }

    LocusSheet(
        title = integration.name,
        subtitle = "Notifications and scheduled task results from your own bot.",
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = !busy && tokenOk && chatId != null,
        onAction = {
            busy = true
            actions.save(token, chatId!!) { r -> r.onSuccess { busy = false; onDismiss() }.onFailure(::failed) }
        },
    ) {
        Text(
            "1. In Telegram, open @BotFather, send /newbot and copy the token it gives you.\n" +
                "2. Open your new bot and press Start (or send it any message).\n" +
                "3. Paste the token here and tap Find my chat.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            token, { token = it.trim(); error = null },
            label = { Text(if (hasKey) "Bot token (blank keeps the saved one)" else integration.keyLabel) },
            singleLine = true, isError = token.isNotBlank() && !Telegram.isToken(token),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy && tokenOk,
                onClick = {
                    busy = true; error = null; note = null
                    actions.findChats(token) { r ->
                        r.onSuccess { d ->
                            busy = false
                            found = d
                            val first = d.chats.firstOrNull()
                            if (first != null) chatText = first.id.toString()
                            else note = "No messages yet. Open " + (d.botUsername?.let { "@$it" } ?: "your bot") + " in Telegram, press Start, then tap Find my chat again."
                        }.onFailure(::failed)
                    }
                },
            ) { Text("Find my chat") }
            TextButton(onClick = { uriHandler.openUri(found?.botUsername?.let { "https://t.me/$it" } ?: integration.signupUrl) }) {
                Text(found?.botUsername?.let { "Open @$it" } ?: integration.linkLabel)
            }
        }
        found?.chats?.takeIf { it.isNotEmpty() }?.let { chats ->
            Column {
                chats.forEach { chat ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chatText = chat.id.toString() }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chatId == chat.id, onClick = { chatText = chat.id.toString() })
                        Text(
                            "${chat.name} · ${if (chat.type == "private") "private chat" else chat.type}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            chatText, { chatText = it.trim(); error = null }, label = { Text("Chat id") }, singleLine = true,
            isError = chatText.isNotBlank() && chatId == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        val type = found?.chats?.firstOrNull { it.id == chatId }?.type
        if ((type != null && type != "private") || (type == null && chatId != null && chatId < 0)) {
            Text(
                "This is a group or channel: everyone in it will see your notifications.",
                style = MaterialTheme.typography.bodySmall, color = Locus.colors.critical,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy && tokenOk && chatId != null,
                onClick = {
                    busy = true; error = null; note = null
                    actions.test(token, chatId!!) { r -> r.onSuccess { busy = false; note = "Sent. Check Telegram." }.onFailure(::failed) }
                },
            ) { Text("Send a test") }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (hasKey) TextButton(onClick = { actions.remove(); onDismiss() }) { Text("Remove", color = Locus.colors.critical) }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            "The token is stored encrypted on this phone and the AI never sees it. Locus only calls Telegram to send you something; " +
                "it doesn't read what you send the bot (except once here, to find your chat).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
