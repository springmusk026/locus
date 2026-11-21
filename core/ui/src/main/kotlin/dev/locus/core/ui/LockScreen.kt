package dev.locus.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusScreen
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SecretContent
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.DeviceAuthOutcome
import dev.locus.core.designsystem.R as DesignR

/** What the lock screen is in front of: all of Locus, or one locked chat. */
enum class LockScreenKind { App, Chat }

/**
 * Shown instead of Locus's content while it's locked, or instead of a locked chat (docs/design/app-lock.md).
 * Nothing of what's behind it is composed. While it's on screen the window is secure ([SecretContent]):
 * no screenshots, and a blank preview in Recents.
 *
 * [busy] while the system prompt is up; [outcome] is the last attempt's, which says why it didn't open
 * (a cancel says nothing). [onBack] adds a back button (a locked chat goes back to the list).
 */
@Composable
fun LockScreen(
    kind: LockScreenKind,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    outcome: DeviceAuthOutcome? = null,
    onBack: (() -> Unit)? = null,
) {
    SecretContent()
    val title = stringResource(if (kind == LockScreenKind.App) R.string.ui_lock_app_title else R.string.ui_lock_chat_title)
    // No top bar (an empty one): the title is the heading in the middle, and names the pane for TalkBack.
    LocusScreen(title = title, modifier = modifier.fillMaxSize(), topBar = {}) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(Spacing.xs)) {
                Icon(LocusIcons.Back, contentDescription = stringResource(DesignR.string.common_back))
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LocusMark(Modifier.padding(bottom = Spacing.xl))
            Text(
                title,
                style = LocusType.stateTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            SupportingText(
                stringResource(if (kind == LockScreenKind.App) R.string.ui_lock_app_body else R.string.ui_lock_chat_body),
                modifier = Modifier.padding(top = Spacing.sm).widthIn(max = Sizes.readable),
                style = LocusType.rowSupporting,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.padding(top = Spacing.xl)) {
                if (busy) InlineSpinner() else Text(stringResource(R.string.ui_lock_unlock))
            }
            lockMessage(outcome)?.let { message ->
                Banner(stringResource(message), Tone.Caution, Modifier.padding(top = Spacing.xl).widthIn(max = Sizes.readable))
            }
        }
    }
    }
}

/** The app's mark in its moss tile, as on the home screen. Its name is the screen's title, so it's decorative. */
@Composable
private fun LocusMark(modifier: Modifier = Modifier) {
    Surface(modifier.size(IconSize.hero), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Icon(painterResource(R.drawable.ui_locus_mark), contentDescription = null, modifier = Modifier.size(IconSize.hero))
    }
}

/** What the lock screen says after an attempt: nothing after a success or a cancel. */
@StringRes
fun lockMessage(outcome: DeviceAuthOutcome?): Int? = when (outcome) {
    null, DeviceAuthOutcome.SUCCEEDED, DeviceAuthOutcome.CANCELLED -> null
    DeviceAuthOutcome.LOCKOUT -> R.string.ui_lock_lockout
    DeviceAuthOutcome.LOCKOUT_PERMANENT -> R.string.ui_lock_lockout_permanent
    DeviceAuthOutcome.NO_CREDENTIAL -> R.string.ui_lock_no_credential
    DeviceAuthOutcome.FAILED -> R.string.ui_lock_failed
}
