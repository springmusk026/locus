package dev.locus.feature.library

import androidx.compose.runtime.Composable
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Tone

/** The badge on a draft held because its files were changed outside Locus. */
internal const val CHANGED_OUTSIDE = "Changed outside Locus"

/**
 * Says why a skill or helper is a draft again: its files were changed outside Locus, so it failed its
 * seal (docs/design/linux-isolation.md, "Sealed instructions"). [approvedActive]: the version the user
 * approved was put back and is still in use.
 */
@Composable
internal fun ChangedOutsideBanner(kind: String, approvedActive: Boolean) {
    Banner(
        "Something other than Locus changed this $kind's files, for example a program in Linux. Locus isn't using this version" +
            (if (approvedActive) ": the one you approved is still active." else ", and no approved copy was left to put back.") +
            " Approve it only if you made this change yourself; otherwise discard it.",
        Tone.Critical,
        title = CHANGED_OUTSIDE,
    )
}

/** Asks before approving a version changed outside Locus. */
@Composable
internal fun ConfirmApproveChanged(name: String, kind: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    LocusSheet(
        title = "Approve a change made outside Locus?",
        subtitle = "Locus will follow the $kind “$name” as it is now, in every new chat. Do this only if you changed it yourself.",
        onDismiss = onDismiss,
        action = "Approve",
        actionTone = Tone.Caution,
        onAction = onConfirm,
    ) {}
}
