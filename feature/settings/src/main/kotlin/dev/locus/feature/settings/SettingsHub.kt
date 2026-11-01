package dev.locus.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ApiFormat

/** The top of Settings: one row per area, each with a one-line summary of its current state. */
@Composable
internal fun SettingsHub(state: SettingsUiState, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val defaultProvider = state.providers.firstOrNull { it.isDefault }?.config
    val missingCredential = state.providers.firstOrNull { it.config.needsCredential && !it.hasKey }?.config
    val version = appVersion()

    SettingsPageScaffold("Settings", onBack) {
        item { SectionLabel("Models", SectionPadding) }
        item {
            CardGroup {
                HubRow(
                    title = "Models",
                    summary = modelsSummary(state),
                    onClick = { onOpen(SettingsPage.Models) },
                    leading = {
                        if (defaultProvider != null) {
                            ProviderBadge(defaultProvider.name, defaultProvider.baseUrl, defaultProvider.format == ApiFormat.ON_DEVICE, size = 36.dp, model = defaultProvider.model)
                        } else {
                            IconTile(LocusIcons.Sparkle)
                        }
                    },
                    pill = when {
                        !state.loading && state.providers.isEmpty() -> ("Set up" to Tone.Caution)
                        missingCredential != null -> ((if (missingCredential.signsIn) "Signed out" else "No key") to Tone.Caution)
                        else -> null
                    },
                )
                GroupDivider(inset = ROW_INSET)
                HubRow("Helper model & extras", extrasSummary(state), LocusIcons.Chats) { onOpen(SettingsPage.Extras) }
                GroupDivider(inset = ROW_INSET)
                // Proxy and certificates apply to model providers only.
                HubRow("Network", networkHubSummary(state.network), LocusIcons.Globe) { onOpen(SettingsPage.Network) }
            }
        }

        item { SectionLabel("Agent", SectionPadding) }
        item {
            CardGroup {
                HubRow("Agent", agentSummary(state), LocusIcons.Note) { onOpen(SettingsPage.Agent) }
                GroupDivider(inset = ROW_INSET)
                HubRow("Permissions", permissionsSummary(state.tools) + reviewerSummary(state.reviewerMode), LocusIcons.Shield) { onOpen(SettingsPage.Permissions) }
            }
        }

        item { SectionLabel("Connections", SectionPadding) }
        item {
            CardGroup {
                HubRow("Integrations & connectors", integrationsSummary(state), LocusIcons.Plug) { onOpen(SettingsPage.Integrations) }
                GroupDivider(inset = ROW_INSET)
                HubRow("SSH machines", sshSummary(state), LocusIcons.Server) { onOpen(SettingsPage.Ssh) }
            }
        }

        item { SectionLabel("Data", SectionPadding) }
        item {
            CardGroup {
                HubRow("Backup & restore", "An encrypted file you keep, and restoring from one", LocusIcons.Restore) { onOpen(SettingsPage.Backup) }
            }
        }

        // No label: a lone "About" label over "About & privacy" would say the same thing twice.
        item { Spacer(Modifier.height(Spacing.xl)) }
        item {
            CardGroup {
                HubRow("About & privacy", "What leaves this phone, and setup", LocusIcons.Key, meta = version?.let { "v$it" }) { onOpen(SettingsPage.About) }
            }
        }
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
) {
    ListRow(
        title = title,
        subtitle = summary,
        onClick = onClick,
        leading = leading,
        trailing = {
            if (pill != null) StatusPill(pill.first, pill.second)
            if (meta != null) Text(meta, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Chevron()
        },
    )
}

@Composable
internal fun appVersion(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
}

// ---- Summaries (plain functions so they are easy to test) ----

/** "OpenAI · default", "OpenAI · default · 3 providers", or "No provider yet". */
internal fun modelsSummary(state: SettingsUiState): String {
    if (state.providers.isEmpty()) return if (state.loading) "" else "No provider yet"
    val default = state.providers.firstOrNull { it.isDefault }?.config
    val head = default?.let { "${it.name} · default" } ?: "No default"
    return if (state.providers.size > 1) "$head · ${state.providers.size} providers" else head
}

internal fun extrasSummary(state: SettingsUiState): String {
    val helper = state.providers.firstOrNull { it.config.id == state.helperProviderId }?.config
    val helperText = helper?.let { "Helper: ${it.name}" } ?: "Helper: same as each chat"
    val on = listOf(state.autoTitle, state.suggestFollowUps, state.reflectAfterTasks).count { it }
    return "$helperText · $on of 3 extras on"
}

internal fun agentSummary(state: SettingsUiState): String =
    if (state.customInstructions.isBlank()) "Identity, instructions and task budget" else "Custom instructions set"

internal fun permissionsSummary(tools: List<ToolRow>): String =
    if (tools.isEmpty()) "" else policySummary(tools.size, tools.groupingBy { it.policy }.eachCount())

internal fun reviewerSummary(mode: dev.locus.core.model.ReviewerMode): String =
    if (mode == dev.locus.core.model.ReviewerMode.OFF) "" else " · Reviewer on"

internal fun integrationsSummary(state: SettingsUiState): String {
    val connected = state.integrations.filter { it.second }.map { it.first.name }
    return when {
        connected.isEmpty() -> "Nothing connected yet"
        connected.size <= 2 -> "${connected.joinToString(", ")} connected"
        else -> "${connected.size} connected"
    }
}

internal fun sshSummary(state: SettingsUiState): String = when (val n = state.sshHosts.size) {
    0 -> "No machines yet"
    1 -> "1 machine · ${state.sshHosts.first().alias}"
    else -> "$n machines"
}
