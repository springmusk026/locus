package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.rememberBefore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import dev.locus.core.designsystem.AppLocale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ApiFormat
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron

/** The top of Settings: one row per area, each with a one-line summary of its current state. */
@Composable
internal fun SettingsHub(state: SettingsUiState, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val defaultProvider = state.providers.firstOrNull { it.isDefault }?.config
    val missingCredential = state.providers.firstOrNull { it.config.needsCredential && !it.hasKey }?.config
    val version = appVersion()
    // Until the settings have loaded, every summary shows the same placeholder rather than a guess.
    val loadingSummary = stringResource(R.string.settings_hub_loading)
    fun summary(text: String) = if (state.loading) loadingSummary else text
    // The per-app language isn't in SettingsUiState: Android keeps it (AppLocale, API 33+), and changing
    // it recreates the screen, which reads it again here. Below 33 Locus follows the phone: no row.
    val context = LocalContext.current
    var languageTags by remember { mutableStateOf(if (AppLocale.supported) AppLocale.get(context) else "") }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(stringResource(R.string.settings_hub_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_hub_section_providers), SectionPadding) }
        item {
            CardGroup {
                HubRow(
                    title = stringResource(R.string.settings_hub_models_title),
                    summary = summary(modelsSummary(state)),
                    onClick = { onOpen(SettingsPage.Models) },
                    leading = {
                        if (defaultProvider != null) {
                            ProviderBadge(defaultProvider.name, defaultProvider.baseUrl, defaultProvider.format == ApiFormat.ON_DEVICE, size = IconSize.tile)
                        } else {
                            IconTile(LocusIcons.Key)
                        }
                    },
                    pill = when {
                        // A draft from the agent waits for the user: first, like Library's own "Needs review".
                        state.providerDrafts.isNotEmpty() -> (stringResource(R.string.settings_hub_pill_needs_review) to Tone.Caution)
                        !state.loading && state.providers.isEmpty() -> (stringResource(R.string.settings_hub_pill_set_up) to Tone.Caution)
                        missingCredential != null -> (stringResource(if (missingCredential.signsIn) R.string.settings_hub_pill_signed_out else R.string.settings_hub_pill_no_key) to Tone.Caution)
                        else -> null
                    },
                )
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_extras_title), summary(extrasSummary(state)), LocusIcons.Chats) { onOpen(SettingsPage.Extras) }
                GroupDivider(inset = Insets.iconRow)
                // Proxy and certificates apply to model providers only.
                HubRow(stringResource(R.string.settings_hub_network_title), summary(networkHubSummary(state.network)), LocusIcons.Globe) { onOpen(SettingsPage.Network) }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_hub_section_how_it_works), SectionPadding) }
        item {
            CardGroup {
                HubRow(stringResource(R.string.settings_hub_agent_title), summary(agentSummary(state)), LocusIcons.Note) { onOpen(SettingsPage.Agent) }
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_permissions_title), summary(permissionsSummary(state.tools, state.reviewerMode)), LocusIcons.Shield) { onOpen(SettingsPage.Permissions) }
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_battery_title), stringResource(R.string.settings_hub_battery_summary), LocusIcons.Gauge) { onOpen(SettingsPage.Battery) }
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_voice_title), stringResource(R.string.settings_hub_voice_summary), LocusIcons.Mic) { onOpen(SettingsPage.Voice) }
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_automation_title), stringResource(R.string.settings_hub_automation_summary), LocusIcons.Play) { onOpen(SettingsPage.Automation) }
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_phone_title), stringResource(R.string.settings_hub_phone_summary), LocusIcons.Phone) { onOpen(SettingsPage.Phone) }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_hub_section_connections), SectionPadding) }
        item {
            CardGroup {
                // Remote mode keeps a connection to Telegram open (battery), so it shows here until it times out.
                val remoteOn by rememberBefore(state.telegramRemote.switchesOffAt)
                HubRow(
                    stringResource(R.string.settings_hub_integrations_title),
                    summary(integrationsSummary(state)),
                    onClick = { onOpen(SettingsPage.Integrations) },
                    leading = { IconTile(LocusIcons.Plug) },
                    chip = if (remoteOn) ({ LocusChip(stringResource(R.string.settings_hub_remote_on), tone = Tone.Live, dot = true, contentDescription = stringResource(R.string.settings_hub_remote_on_description)) }) else null,
                )
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_ssh_title), summary(sshSummary(state)), LocusIcons.Server) { onOpen(SettingsPage.Ssh) }
                GroupDivider(inset = Insets.iconRow)
                HubRow(stringResource(R.string.settings_hub_logins_title), stringResource(R.string.settings_hub_logins_summary), LocusIcons.Key) { onOpen(SettingsPage.Logins) }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_hub_section_data), SectionPadding) }
        item {
            CardGroup {
                HubRow(stringResource(R.string.settings_hub_backup_title), stringResource(R.string.settings_hub_backup_summary), LocusIcons.Restore) { onOpen(SettingsPage.Backup) }
            }
        }

        if (AppLocale.supported) {
            item { Spacer(Modifier.height(Spacing.xl)) }
            item {
                CardGroup {
                    HubRow(stringResource(R.string.settings_hub_language_title), languageSummaryText(languageTags), LocusIcons.Globe) { choosingLanguage = true }
                }
        }
        }

        // No label: a lone "About" label over "About & privacy" would say the same thing twice.
        item { Spacer(Modifier.height(Spacing.xl)) }
        item {
            CardGroup {
                HubRow(stringResource(R.string.settings_hub_about_title), stringResource(R.string.settings_hub_about_summary), LocusIcons.Info, meta = version?.let { stringResource(R.string.settings_hub_version, it) }) { onOpen(SettingsPage.About) }
            }
        }
    }

    if (choosingLanguage) {
        LanguageSheet(
            current = languageTags,
            onPick = { tag ->
                choosingLanguage = false
                languageTags = tag
                if (AppLocale.supported) AppLocale.set(context, tag)
            },
            onDismiss = { choosingLanguage = false },
        )
    }
}

@Composable
private fun HubRow(title: String, summary: String, icon: ImageVector, meta: String? = null, onClick: () -> Unit) {
    HubRow(title, summary, onClick, leading = { IconTile(icon) }, meta = meta)
}

@Composable
private fun HubRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    pill: Pair<String, Tone>? = null,
    meta: String? = null,
    chip: (@Composable () -> Unit)? = null,
) {
    // At a large font the pill and chip go under the summary, so the title keeps the row's width.
    val large = LocalDensity.current.fontScale > LARGE_FONT
    ListRow(
        title = title,
        subtitle = summary,
        // A summary isn't cut at 360dp, and a title isn't cut at a large font.
        titleMaxLines = 2,
        subtitleMaxLines = if (large) 4 else 2,
        onClick = onClick,
        leading = leading,
        meta = if (large && (pill != null || chip != null)) ({
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (pill != null) StatusPill(pill.first, pill.second)
                chip?.invoke()
            }
        }) else null,
        trailing = {
            if (!large) {
                if (pill != null) StatusPill(pill.first, pill.second)
                chip?.invoke()
            }
            if (meta != null) SupportingText(meta, style = LocusType.mono)
            RowChevron()
        },
    )
}

/** From this font scale a hub row's pill moves under its text. */
private const val LARGE_FONT = 1.3f

@Composable
internal fun appVersion(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
}

// ---- Summaries (plain functions so they are easy to test) ----

/**
 * "OpenAI · default", "OpenAI · default · 3 providers", or "No provider yet". When a provider has no
 * key (or is signed out) it says which, since the row's "No key" pill sits beside the default's logo.
 */
@Composable
internal fun modelsSummary(state: SettingsUiState): String {
    val drafts = state.providerDrafts.size
    if (drafts > 0) {
        val waiting = if (drafts == 1) {
            stringResource(R.string.settings_hub_models_draft_named, state.providerDrafts[0].draft.name)
        } else {
            pluralStringResource(R.plurals.settings_hub_models_drafts, drafts, drafts)
        }
        val default = state.providers.firstOrNull { it.isDefault }?.config
        return if (default == null) waiting else summaryJoin(waiting, stringResource(R.string.settings_hub_models_default, default.name))
    }
    if (state.providers.isEmpty()) return if (state.loading) "" else stringResource(R.string.settings_hub_models_none)
    val default = state.providers.firstOrNull { it.isDefault }?.config
    val head = default?.let { stringResource(R.string.settings_hub_models_default, it.name) } ?: stringResource(R.string.settings_hub_models_no_default)
    val missing = state.providers.firstOrNull { it.config.needsCredential && !it.hasKey }?.config
    return when {
        // First, so it's what the two lines keep: the pill beside it means this provider.
        missing != null -> summaryJoin(
            stringResource(if (missing.signsIn) R.string.settings_hub_models_signed_out else R.string.settings_hub_models_no_key, missing.name),
            head,
        )
        state.providers.size > 1 -> summaryJoin(head, pluralStringResource(R.plurals.settings_hub_models_providers, state.providers.size, state.providers.size))
        else -> head
    }
}

@Composable
internal fun extrasSummary(state: SettingsUiState): String {
    val helper = state.providers.firstOrNull { it.config.id == state.helperProviderId }?.config
    val helperText = helper?.let { stringResource(R.string.settings_hub_extras_helper_named, it.name) }
        ?: if (state.helperAutomatic) stringResource(R.string.settings_hub_extras_helper_automatic) else stringResource(R.string.settings_hub_extras_helper_chat_model)
    val extras = listOf(state.autoTitle, state.suggestFollowUps, state.reflectAfterTasks)
    return summaryJoin(helperText, stringResource(R.string.settings_hub_extras_count, extras.count { it }, extras.size))
}

@Composable
internal fun agentSummary(state: SettingsUiState): String =
    stringResource(if (state.customInstructions.isBlank()) R.string.settings_hub_agent_summary else R.string.settings_hub_agent_custom)

@Composable
internal fun permissionsSummary(tools: List<ToolRow>, mode: dev.locus.core.model.ReviewerMode): String {
    val base = if (tools.isEmpty()) stringResource(R.string.settings_hub_permissions_summary) else policySummary(tools.size, tools.groupingBy { it.policy }.eachCount())
    return if (mode == dev.locus.core.model.ReviewerMode.OFF) base else summaryJoin(base, stringResource(R.string.settings_hub_permissions_reviewer_on))
}

@Composable
internal fun integrationsSummary(state: SettingsUiState): String {
    val connected = state.integrations.filter { it.second }.map { it.first.name }
    return when {
        connected.isEmpty() -> stringResource(R.string.settings_hub_integrations_none)
        connected.size <= 2 -> stringResource(R.string.settings_hub_integrations_names, connected.joinToString(", "))
        else -> stringResource(R.string.settings_hub_integrations_count, connected.size)
    }
}

@Composable
internal fun sshSummary(state: SettingsUiState): String = when (val n = state.sshHosts.size) {
    0 -> stringResource(R.string.settings_hub_ssh_none)
    1 -> summaryJoin(pluralStringResource(R.plurals.settings_hub_ssh_machines, 1, 1), state.sshHosts.first().alias)
    else -> pluralStringResource(R.plurals.settings_hub_ssh_machines, n, n)
}
