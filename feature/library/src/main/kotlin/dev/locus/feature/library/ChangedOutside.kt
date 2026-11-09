package dev.locus.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Tone

/**
 * Says why a skill or helper is a draft again: its files were changed outside Locus, so it failed its
 * seal (docs/design/linux-isolation.md, "Sealed instructions"). [approvedActive]: the version the user
 * approved was put back and is still in use.
 */
@Composable
internal fun ChangedOutsideBanner(kind: String, approvedActive: Boolean) {
    Banner(
        stringResource(
            if (approvedActive) R.string.library_changed_outside_banner_active else R.string.library_changed_outside_banner_lost,
            kind,
        ),
        Tone.Critical,
        title = stringResource(R.string.library_changed_outside_badge),
    )
}

/** Asks before approving a version changed outside Locus. */
@Composable
internal fun ConfirmApproveChanged(name: String, kind: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    LocusSheet(
        title = stringResource(R.string.library_changed_outside_confirm_title),
        subtitle = stringResource(R.string.library_changed_outside_confirm_body, kind, name),
        onDismiss = onDismiss,
        action = stringResource(R.string.library_action_approve),
        actionTone = Tone.Caution,
        onAction = onConfirm,
    ) {}
}
