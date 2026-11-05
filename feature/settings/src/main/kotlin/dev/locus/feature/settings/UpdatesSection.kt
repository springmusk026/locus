package dev.locus.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.locus.core.data.updates.Release
import dev.locus.core.data.updates.UpdateAvailability
import dev.locus.core.data.updates.UpdateProblem
import dev.locus.core.data.updates.UpdateState
import dev.locus.core.designsystem.Banner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.MeterSize
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.SettingsActionFor
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.markdown.documentTypography

/**
 * Settings → About & privacy → Updates (docs/design/updates.md): the opt-in daily check, Check now
 * (which works with it off), and the release in hand from "Update available" to the installer.
 */
internal fun LazyListScope.updatesSection(ui: UpdatesUiState, actions: UpdateActions) {
    val info = ui.info ?: return
    item { SectionLabel("Updates", SectionPadding) }
    item {
        CardGroup {
            val note = availabilityNote(info.availability)
            if (note != null) {
                ListRow(availabilityTitle(info.availability), subtitle = note, titleMaxLines = 2, subtitleMaxLines = Int.MAX_VALUE, leading = { IconTile(LocusIcons.Info) })
            }
            if (info.availability == UpdateAvailability.Available) {
                SwitchRow(
                    title = "Check GitHub for updates",
                    subtitle = "About once a day on Wi-Fi. One request to GitHub, with nothing about you; you choose whether to install.",
                    checked = info.enabled,
                    onCheckedChange = actions.setEnabled,
                    icon = LocusIcons.Globe,
                )
            }
            if (info.availability.canCheck) {
                GroupDivider(Insets.iconRow)
                val checking = ui.state == UpdateState.Checking
                val busy = ui.state is UpdateState.Downloading || ui.state is UpdateState.Verifying || ui.state is UpdateState.Installing
                ListRow(
                    title = "Check now",
                    subtitle = updateStatusLine(info, ui.state, System.currentTimeMillis()) { t, n -> relativeTime(t, n) },
                    subtitleMaxLines = 2,
                    onClick = actions.checkNow,
                    enabled = !checking && !busy,
                    leading = { IconTile(LocusIcons.Regenerate) },
                    trailing = if (checking) ({ InlineSpinner(contentDescription = "Checking for updates") }) else null,
                )
            }
        }
    }
    when (val s = ui.state) {
        is UpdateState.Available -> if (s.skipped) {
            item {
                SectionNote("You skipped ${s.release.version}. Locus won't mention it again; a newer version will show.", NotePadding)
                TextButton(onClick = actions.unskip, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Show ${s.release.version} again") }
            }
        } else {
            item { ReleaseCard(s.release, info.availability, ui.state, actions) }
        }
        is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.ReadyToInstall, is UpdateState.Installing ->
            item { ReleaseCard(releaseOf(s)!!, info.availability, s, actions) }
        is UpdateState.Failed -> item { ProblemBanner(s, actions) }
        else -> Unit
    }
}

/** What the Updates section can do; the view model's functions in the app, no-ops in previews. */
internal data class UpdateActions(
    val setEnabled: (Boolean) -> Unit = {},
    val checkNow: () -> Unit = {},
    val download: () -> Unit = {},
    val cancel: () -> Unit = {},
    val install: () -> Unit = {},
    val skip: (String) -> Unit = {},
    val unskip: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val retry: () -> Unit = {},
)

internal fun UpdatesViewModel.actions() = UpdateActions(
    setEnabled = { setEnabled(it) },
    checkNow = { checkNow() },
    download = ::download,
    cancel = ::cancelDownload,
    install = ::install,
    skip = { skip(it) },
    unskip = { unskip() },
    dismiss = ::dismissProblem,
    retry = { retry() },
)

/** Re-checks "Install unknown apps" when the user comes back from Android's settings. */
@Composable
internal fun UpdatesResumeEffect(viewModel: UpdatesViewModel) {
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
}

@Composable
private fun ReleaseCard(release: Release, availability: UpdateAvailability, state: UpdateState, actions: UpdateActions) {
    val uri = LocalUriHandler.current
    LocusCard(Modifier.padding(top = Spacing.sm), glow = MaterialTheme.colorScheme.primary) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Update available: ${release.version}", Modifier.semantics { heading() }, style = LocusType.cardTitle)
            // Only the date is set in mono; the words around it read as words.
            release.publishedAt?.take(10)?.let { date ->
                SupportingText(
                    buildAnnotatedString {
                        append("Released ")
                        withStyle(SpanStyle(fontFamily = LocusType.mono.fontFamily)) { append(date) }
                        append(" on GitHub")
                    },
                )
            }
            when (state) {
                is UpdateState.Downloading -> {
                    Meter(state.fraction, tone = Tone.Live, size = MeterSize.Medium, contentDescription = "Download progress")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SupportingText(downloadProgress(state.bytes, state.total), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = actions.cancel) { Text("Cancel") }
                    }
                }
                is UpdateState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    InlineSpinner()
                    SupportingText("Checking the download's SHA-256 and signature…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                is UpdateState.ReadyToInstall -> {
                    SupportingText("Verified: the checksum matches the release and it's signed with the same key as this Locus. Android will ask you to confirm.")
                    Button(onClick = actions.install, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) { Text("Install update") }
                }
                is UpdateState.Installing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    InlineSpinner()
                    SupportingText("Waiting for Android's installer. Locus restarts once the update is in.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                else -> {
                    if (availability == UpdateAvailability.DebugBuild) {
                        SupportingText("Debug build: updates come from your own builds, so there's nothing to install here.")
                    }
                    // The main action is full width, like "Install update" in the same card.
                    if (availability == UpdateAvailability.Available) {
                        Button(onClick = actions.download, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) { Text("Download and verify") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        TextButton(onClick = { actions.skip(release.version) }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Skip this version") }
                        TextButton(onClick = { uri.openUri(release.htmlUrl.ifBlank { RELEASES_PAGE }) }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Release page") }
                    }
                }
            }
        }
    }
    val notes = withoutLeadingHeading(release.body?.trim().orEmpty())
    if (notes.isNotEmpty()) {
        CardGroup(Modifier.padding(top = Spacing.sm)) {
            ExpandableRow(
                title = "What's new in ${release.version}",
                subtitle = "Release notes from GitHub",
                icon = LocusIcons.Note,
                startExpanded = true,
                expandKey = release.version,
                iconDescription = "Release notes",
            ) {
                ReleaseNotes(notes)
            }
        }
    }
}

/** The row that says where updates come from when this build doesn't install them itself. */
private fun availabilityTitle(availability: UpdateAvailability): String = when (availability) {
    is UpdateAvailability.FromStore -> "Updated by ${availability.store}"
    UpdateAvailability.DebugBuild -> "Debug build"
    UpdateAvailability.DisabledInBuild, UpdateAvailability.Available -> "Updated by your app store"
}

/** Release notes without their own first heading ("## What's new"): the row above already says it. */
internal fun withoutLeadingHeading(markdown: String): String {
    val first = markdown.lineSequence().firstOrNull() ?: return markdown
    val heading = first.trimStart()
    val repeats = heading.startsWith("#") && heading.trimStart('#').trim().lowercase().replace('’', '\'').startsWith("what's new")
    return if (repeats) markdown.substringAfter('\n', "").trimStart() else markdown
}

/** The release notes, rendered with the app's Markdown renderer at card size. */
@Composable
private fun ReleaseNotes(markdown: String) {
    val state = rememberMarkdownState(markdown, retainState = true, immediate = true)
    Markdown(state, typography = documentTypography(body = LocusType.rowSupporting, compact = true), modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm))
}

@Composable
private fun ProblemBanner(state: UpdateState.Failed, actions: UpdateActions) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val message = problemMessage(state.problem) { timeFormat.format(java.util.Date(it)) }
    val release = state.release
    val tone = when (state.problem) {
        UpdateProblem.InstallCancelled, is UpdateProblem.RateLimited, UpdateProblem.InstallNotAllowed -> Tone.Caution
        else -> Tone.Critical
    }
    val title = when (state.problem) {
        UpdateProblem.SignatureMismatch -> "Signed with a different key"
        UpdateProblem.ChecksumMismatch -> "Checksum didn't match"
        UpdateProblem.InstallNotAllowed -> "Allow Locus to install apps"
        UpdateProblem.NoNetwork -> "No connection"
        UpdateProblem.InstallCancelled -> "Update cancelled"
        else -> if (release != null) "Couldn't update to ${release.version}" else "Couldn't check for updates"
    }
    Banner(
        text = message,
        tone = tone,
        title = title,
        modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
        actionsBelow = true,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = actions.dismiss) { Text("Dismiss") }
            when (state.problem) {
                UpdateProblem.InstallNotAllowed -> TextButton(onClick = {
                    // Android 8+'s per-app "Install unknown apps" page for Locus.
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Open setting") }
                UpdateProblem.SignatureMismatch -> SettingsActionFor(message)
                UpdateProblem.InstallCancelled -> TextButton(onClick = actions.install) { Text("Install again") }
                UpdateProblem.NoNetwork, UpdateProblem.ChecksumMismatch, UpdateProblem.Storage, is UpdateProblem.Server, is UpdateProblem.InstallFailed ->
                    TextButton(onClick = actions.retry) { Text("Try again") }
                is UpdateProblem.NoApkForDevice, UpdateProblem.NoChecksum, UpdateProblem.BadApk ->
                    TextButton(onClick = { uri.openUri(release?.htmlUrl?.ifBlank { null } ?: RELEASES_PAGE) }) { Text("Release page") }
                is UpdateProblem.RateLimited -> Unit
            }
        }
    }
}
