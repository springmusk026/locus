package dev.locus.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.BrandLogo
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.ContinueWithChatGptButton
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LogoTile
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
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
    // A problem to show, and whether it's a failure (sign-in) or only a caution (an unconfirmed sign-out).
    var message by remember { mutableStateOf<String?>(null) }
    var messageFailed by remember { mutableStateOf(true) }
    var welcome by remember { mutableStateOf(false) }
    var signInJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    if (signedIn) {
        // The account and what can be done with it, in one card.
        CardGroup {
            ListRow(
                title = email ?: "ChatGPT account",
                subtitle = "Using your ChatGPT plan",
                titleMaxLines = 2,
                subtitleMaxLines = 2,
                leading = { LogoTile(BrandLogo.OpenAI, size = IconSize.tile) },
                // Under the text, so the address has the row's whole width.
                meta = { StatusPill("Signed in", Tone.Live) },
            )
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = "Manage usage",
                subtitle = "Opens ChatGPT settings",
                subtitleMaxLines = 2,
                onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) },
                leading = { IconTile(LocusIcons.Gauge) },
                trailing = { RowChevron() },
            )
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = "Sign out",
                subtitle = "This provider stops working until you sign in again",
                subtitleMaxLines = 2,
                onClick = { confirmSignOut = true },
                enabled = !busy,
                leading = { IconTile(LocusIcons.Close) },
            )
        }
    } else {
        SupportingText(
            "Use your ChatGPT Plus or Pro plan for this provider's requests, instead of an API key. Usage counts toward your plan's limits.",
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
                        if (it !is kotlinx.coroutines.CancellationException) {
                            messageFailed = true
                            message = it.message ?: "The sign-in didn't finish. Try Continue with ChatGPT again."
                        }
                    }
                }
            },
        )
        // Changed their mind in the browser: stop waiting for it.
        if (busy && signInJob != null) {
            TextButton(onClick = { signInJob?.cancel(); signInJob = null; busy = false }) { Text("Cancel") }
        }
    }
    message?.let {
        Banner(
            text = it,
            tone = if (messageFailed) Tone.Critical else Tone.Caution,
            title = if (messageFailed) "Couldn't sign in" else "Sign-out not confirmed",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (confirmSignOut) {
        ConfirmSheet(
            title = "Sign out of ChatGPT?",
            message = "Locus deletes the sign-in from this phone and asks OpenAI to revoke it. This provider stops working until you sign in again.",
            confirmLabel = "Sign out",
            tone = Tone.Critical,
            onConfirm = {
                confirmSignOut = false
                busy = true
                actions.signOut(providerId) { confirmed ->
                    busy = false
                    messageFailed = false
                    message = if (confirmed) null else "Signed out on this phone, but OpenAI didn't confirm it. You can also disconnect Locus in ChatGPT settings."
                }
            },
            onDismiss = { confirmSignOut = false },
        )
    }

    if (welcome) {
        // An information sheet: the words first, then the button that closes it.
        LocusSheet(title = "You're using your ChatGPT plan", onDismiss = { welcome = false }) {
            SupportingText("Requests to this provider now use your ChatGPT plan. You can review and manage that usage in ChatGPT settings.")
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Manage usage") }
            Button(onClick = { welcome = false }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) { Text("Got it") }
        }
    }
}
