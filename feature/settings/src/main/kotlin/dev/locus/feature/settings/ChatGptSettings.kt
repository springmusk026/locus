package dev.locus.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.BrandLogo
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ContinueWithChatGptButton
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LogoTile
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.llm.ChatGptErrors

/** What the provider editor can do with a ChatGPT provider's sign-in. */
internal class ChatGptActions(
    /** Runs the browser sign-in; the result is true for a first sign-in (a new registration). Cancel the returned job to stop waiting. */
    val signIn: (providerId: String, onResult: (Result<Boolean>) -> Unit) -> kotlinx.coroutines.Job?,
    /** Signs out; the result is false when OpenAI couldn't confirm the revocation. */
    val signOut: (providerId: String, onResult: (Boolean) -> Unit) -> Unit,
    /** Drops the sign-in of a provider that was never saved. */
    val discard: (providerId: String) -> Unit,
) {
    companion object {
        val None = ChatGptActions({ _, _ -> null }, { _, _ -> }, {})
    }
}

/**
 * The ChatGPT part of the provider editor, in place of the API key: "Continue with ChatGPT" when
 * signed out, then the account with Manage usage and Sign out. After a first sign-in it confirms
 * once that requests now use the user's plan, as OpenAI's UI guidelines ask.
 */
@Composable
internal fun ChatGptAccountSection(providerId: String, signedIn: Boolean, email: String?, actions: ChatGptActions) {
    val uriHandler = LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var welcome by remember { mutableStateOf(false) }
    var signInJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    if (signedIn) {
        CardGroup {
            ListRow(
                title = email ?: "ChatGPT account",
                subtitle = "Using your ChatGPT plan",
                leading = { LogoTile(BrandLogo.OpenAI, size = 36.dp) },
                trailing = { StatusPill("Signed in", Tone.Live) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }) { Text("Manage usage") }
            Box(Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { confirmSignOut = true }) { Text("Sign out", color = Locus.colors.critical) }
        }
    } else {
        Text(
            "Use your ChatGPT Plus or Pro plan for this provider's requests, instead of an API key. Usage counts toward your plan's limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ContinueWithChatGptButton(
            busy = busy,
            onClick = {
                busy = true
                message = null
                signInJob = actions.signIn(providerId) { result ->
                    busy = false
                    signInJob = null
                    result.onSuccess { first -> welcome = first }.onFailure {
                        // Cancelled by the user: nothing to report.
                        if (it !is kotlinx.coroutines.CancellationException) message = it.message ?: "Sign-in failed."
                    }
                }
            },
        )
        // Changed their mind in the browser: stop waiting for it.
        if (busy && signInJob != null) {
            TextButton(onClick = { signInJob?.cancel(); signInJob = null; busy = false }) { Text("Cancel") }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Locus.colors.critical) }

    if (confirmSignOut) {
        LocusSheet(
            title = "Sign out of ChatGPT?",
            subtitle = "Locus deletes the sign-in from this phone and asks OpenAI to revoke it. This provider stops working until you sign in again.",
            onDismiss = { confirmSignOut = false },
            action = "Sign out",
            actionTone = Tone.Critical,
            onAction = {
                confirmSignOut = false
                busy = true
                actions.signOut(providerId) { confirmed ->
                    busy = false
                    message = if (confirmed) null else "Signed out on this phone, but OpenAI didn't confirm it. You can also disconnect Locus in ChatGPT settings."
                }
            },
        ) {}
    }

    if (welcome) {
        LocusSheet(
            title = "You're using your ChatGPT plan",
            subtitle = "Requests to this provider now use your ChatGPT plan. You can review and manage that usage in ChatGPT settings.",
            onDismiss = { welcome = false },
            action = "Got it",
            onAction = { welcome = false },
        ) {
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }) { Text("Manage usage") }
        }
    }
}
