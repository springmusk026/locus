package dev.locus.feature.library

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.locus.core.data.mcp.LocalMcpHealth
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.ui.descriptionText
import dev.locus.core.ui.displayName

/**
 * A known-good local server, offered as a hint. Picking one fills the form; nothing is installed. [nameRes] and
 * [needsRes] are [name] and [needs] in the app's language, where they are words.
 */
internal data class LocalMcpExample(
    val name: String,
    val command: String,
    val needs: String,
    @param:StringRes val nameRes: Int? = null,
    @param:StringRes val needsRes: Int? = null,
) {
    fun nameIn(r: Resources): String = nameRes?.let(r::getString) ?: name

    fun needsIn(r: Resources): String = needsRes?.let(r::getString) ?: needs
}

internal val LOCAL_MCP_EXAMPLES = listOf(
    LocalMcpExample("Files", "npx -y @modelcontextprotocol/server-filesystem /workspace", "Node.js: apk add nodejs npm", R.string.library_localmcp_example_files),
    LocalMcpExample("Memory", "npx -y @modelcontextprotocol/server-memory", "Node.js: apk add nodejs npm", R.string.library_localmcp_example_memory),
    LocalMcpExample("Fetch", "uvx mcp-server-fetch", "uv: apk add uv", R.string.library_localmcp_example_fetch),
    LocalMcpExample("Git", "uvx mcp-server-git --repository /workspace", "uv and git: apk add uv git", needsRes = R.string.library_localmcp_example_git_needs),
)

/** The local-server part of the Add an MCP server sheet. */
internal class LocalMcpForm {
    var command by mutableStateOf("")
    var args by mutableStateOf("")
    val env = mutableStateListOf<Pair<String, String>>()

    fun argList() = args.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun envPairs() = env.filter { it.first.isNotBlank() }.map { it.first.trim() to it.second }

    val isValid: Boolean
        get() = command.isNotBlank() && '\n' !in command &&
            env.all { (k, v) -> k.isBlank() || (ENV_NAME.matches(k.trim()) && v.isNotBlank()) } &&
            envPairs().map { it.first }.distinct().size == envPairs().size

    companion object {
        private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

        /** Keeps the form across rotation: command, arguments, then each variable's name and value. */
        val Saver = listSaver<LocalMcpForm, String>(
            save = { f -> listOf(f.command, f.args) + f.env.flatMap { listOf(it.first, it.second) } },
            restore = { saved ->
                LocalMcpForm().apply {
                    command = saved[0]
                    args = saved[1]
                    saved.drop(2).chunked(2).forEach { (k, v) -> env.add(k to v) }
                }
            },
        )
    }
}

@Composable
internal fun ColumnScope.LocalMcpFields(form: LocalMcpForm, linuxInstalled: Boolean, onExample: (LocalMcpExample) -> Unit) {
    if (!linuxInstalled) {
        Banner(
            stringResource(R.string.library_localmcp_linux_missing_body, stringResource(dev.locus.core.linux.R.string.linux_install_path)),
            Tone.Caution,
            title = stringResource(R.string.library_localmcp_linux_missing_title),
        )
    }
    OutlinedTextField(
        form.command, { form.command = it.replace("\n", " ") },
        label = { Text(stringResource(R.string.library_localmcp_command_label)) },
        supportingText = { Text(stringResource(R.string.library_localmcp_command_hint)) },
        textStyle = LocusType.code,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.args, { form.args = it },
        label = { Text(stringResource(R.string.library_localmcp_args_label)) },
        supportingText = { Text(stringResource(R.string.library_localmcp_args_hint)) },
        textStyle = LocusType.code,
        minLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.library_localmcp_env_title), style = LocusType.cardTitle)
    SupportingText(stringResource(R.string.library_localmcp_env_hint))
    form.env.forEachIndexed { i, (key, value) ->
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(key, { form.env[i] = it.trim() to value }, label = { Text(stringResource(R.string.library_localmcp_env_name)) }, singleLine = true, textStyle = LocusType.code, modifier = Modifier.weight(1f))
            PasswordField(value, { form.env[i] = key to it }, label = stringResource(R.string.library_localmcp_env_value), compact = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { form.env.removeAt(i) }) {
                Icon(LocusIcons.Trash, contentDescription = stringResource(R.string.library_localmcp_env_remove, key.ifBlank { stringResource(R.string.library_localmcp_env_variable) }))
            }
        }
    }
    TextButton(onClick = { form.env.add("" to "") }) { Text(stringResource(R.string.library_localmcp_env_add)) }

    Text(stringResource(R.string.library_localmcp_examples_title), style = LocusType.cardTitle)
    SupportingText(stringResource(R.string.library_localmcp_examples_hint))
    LOCAL_MCP_EXAMPLES.forEach { ex ->
        Column(Modifier.fillMaxWidth().clickable { onExample(ex) }.padding(vertical = Spacing.xs)) {
            Text(ex.command, style = LocusType.code)
            SupportingText(stringResource(R.string.library_localmcp_example_needs, ex.nameIn(LocalResources.current), ex.needsIn(LocalResources.current)))
        }
    }
}

/** A local MCP server in Library → Connectors: what it runs, its secrets, and its health. */
@Composable
internal fun LocalMcpRow(
    entry: ConnectorEntry,
    onAddSecret: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRemove: () -> Unit,
    /** Tools the server changed or added since the user approved it (docs/design/mcp-connectors.md, "Tool pinning"). */
    toolChanges: List<dev.locus.core.data.mcp.PendingTool> = emptyList(),
    onReviewTools: () -> Unit = {},
) {
    val c = entry.connector
    val local = c.local ?: return
    val health = entry.health
    val (badge, tone) = when {
        entry.draft -> stringResource(R.string.library_badge_needs_review) to Tone.Caution
        toolChanges.isNotEmpty() -> toolChangesBadge(toolChanges) to Tone.Caution
        !entry.hasSecret -> stringResource(R.string.library_connectors_badge_needs_key) to Tone.Caution
        health is LocalMcpHealth.Running -> stringResource(R.string.library_localmcp_badge_running) to Tone.Live
        health is LocalMcpHealth.Starting -> stringResource(R.string.library_localmcp_badge_starting) to Tone.Brand
        health is LocalMcpHealth.Failed -> stringResource(R.string.library_localmcp_badge_failed) to Tone.Critical
        else -> stringResource(R.string.library_localmcp_badge_stopped) to Tone.Neutral
    }
    ExpandableRow(
        title = c.displayName(LocalResources.current),
        subtitle = stringResource(R.string.library_localmcp_subtitle),
        leading = { ConnectorTile(c) },
        badge = badge,
        badgeTone = tone,
        startExpanded = entry.draft || toolChanges.isNotEmpty(),
        expandKey = if (entry.draft) "draft" else if (toolChanges.isNotEmpty()) "tools" else "active",
    ) {
        if (toolChanges.isNotEmpty()) ToolChangesBanner(toolChanges, onReview = onReviewTools)
        Text(c.descriptionText(LocalResources.current), style = LocusType.rowSupporting)
        SupportingText(
            stringResource(if (entry.draft) R.string.library_localmcp_command_draft else R.string.library_localmcp_command_active),
        )
        Text((listOf(local.command) + local.args.map { "'$it'" }).joinToString(" "), style = LocusType.code)
        local.env.forEach { e ->
            val missing = e.secret in entry.missingSecrets
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The variable the server reads, and whether its key is saved; the vault's own id stays out of sight.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(e.name, style = LocusType.code)
                    if (missing) Text(stringResource(R.string.library_localmcp_key_missing), style = LocusType.supporting, color = Locus.colors.onCautionContainer)
                    else SupportingText(stringResource(R.string.library_localmcp_key_saved))
                }
                TextButton(onClick = { onAddSecret(e.secret) }) { Text(stringResource(if (missing) R.string.library_action_add else R.string.library_action_replace)) }
            }
        }
        if (!entry.draft) HealthNote(health)
        // Actions wrap to a second line at a large font instead of running off the card.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (entry.draft) {
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.library_action_reject)) }
                Button(onClick = onApprove) { Text(stringResource(R.string.library_action_approve)) }
            } else {
                when (health) {
                    // Stopping while it starts cancels the start (or stops it as soon as a chat's start finishes).
                    is LocalMcpHealth.Running, LocalMcpHealth.Starting -> TextButton(onClick = onStop) { Text(stringResource(R.string.library_localmcp_stop)) }
                    else -> TextButton(onClick = onStart, enabled = entry.hasSecret) { Text(stringResource(if (health is LocalMcpHealth.Failed) R.string.library_action_try_again else R.string.library_localmcp_start)) }
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.library_action_remove), color = Locus.colors.critical) }
            }
        }
    }
}

@Composable
private fun HealthNote(health: LocalMcpHealth?) {
    when (health) {
        is LocalMcpHealth.Running -> Text(
            stringResource(R.string.library_localmcp_health_running),
            style = LocusType.supporting,
        )
        LocalMcpHealth.Starting -> Text(stringResource(R.string.library_localmcp_health_starting), style = LocusType.supporting)
        is LocalMcpHealth.Failed -> {
            Text(stringResource(R.string.library_localmcp_health_failed, health.reason), style = LocusType.supporting, color = Locus.colors.critical)
            if (health.stderrTail.isNotBlank()) {
                SupportingText(stringResource(R.string.library_localmcp_health_stderr))
                Text(health.stderrTail.takeLast(1_200), style = LocusType.code, modifier = Modifier.padding(start = Spacing.xs))
            }
        }
        is LocalMcpHealth.Stopped, null -> {
            val reason = (health as? LocalMcpHealth.Stopped)?.reason
            SupportingText(
                if (reason != null) stringResource(R.string.library_localmcp_health_stopped_reason, reason)
                else stringResource(R.string.library_localmcp_health_stopped),
            )
        }
    }
}
