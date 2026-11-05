package dev.locus.feature.library

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.data.mcp.LocalMcpHealth
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.LINUX_INSTALL_PATH

/** A known-good local server, offered as a hint. Picking one fills the form; nothing is installed. */
internal data class LocalMcpExample(val name: String, val command: String, val needs: String)

internal val LOCAL_MCP_EXAMPLES = listOf(
    LocalMcpExample("Files", "npx -y @modelcontextprotocol/server-filesystem /workspace", "Node.js: apk add nodejs npm"),
    LocalMcpExample("Memory", "npx -y @modelcontextprotocol/server-memory", "Node.js: apk add nodejs npm"),
    LocalMcpExample("Fetch", "uvx mcp-server-fetch", "uv: apk add uv"),
    LocalMcpExample("Git", "uvx mcp-server-git --repository /workspace", "uv and git: apk add uv git"),
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
            "Local servers run in Locus's Linux system, which isn't installed yet. Install it from $LINUX_INSTALL_PATH. You can add the server now; it can't start until then.",
            Tone.Caution,
            title = "Linux isn't installed",
        )
    }
    OutlinedTextField(
        form.command, { form.command = it.replace("\n", " ") },
        label = { Text("Command") },
        supportingText = { Text("Runs in /workspace inside Linux, e.g. an npx or uvx command. It sees only the Linux system and your workspace.") },
        textStyle = LocusType.code,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.args, { form.args = it },
        label = { Text("Extra arguments (optional)") },
        supportingText = { Text("One per line; each is passed as a single argument.") },
        textStyle = LocusType.code,
        minLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Environment variables", style = LocusType.cardTitle)
    SupportingText(
        "For API keys the server needs. Values are stored encrypted in the vault and given to the process when it starts; the AI never sees them.",
    )
    form.env.forEachIndexed { i, (key, value) ->
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(key, { form.env[i] = it.trim() to value }, label = { Text("Name") }, singleLine = true, textStyle = LocusType.code, modifier = Modifier.weight(1f))
            OutlinedTextField(value, { form.env[i] = key to it }, label = { Text("Value") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            IconButton(onClick = { form.env.removeAt(i) }) {
                Icon(LocusIcons.Trash, contentDescription = "Remove ${key.ifBlank { "variable" }}")
            }
        }
    }
    TextButton(onClick = { form.env.add("" to "") }) { Text("Add variable") }

    Text("Examples", style = LocusType.cardTitle)
    SupportingText(
        "Tap one to fill the command. Locus doesn't install anything: install what it needs once with run_command or the Linux terminal.",
    )
    LOCAL_MCP_EXAMPLES.forEach { ex ->
        Column(Modifier.fillMaxWidth().clickable { onExample(ex) }.padding(vertical = Spacing.xs)) {
            Text(ex.command, style = LocusType.code)
            SupportingText("${ex.name} · needs ${ex.needs}")
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
) {
    val c = entry.connector
    val local = c.local ?: return
    val health = entry.health
    val (badge, tone) = when {
        entry.draft -> "Needs review" to Tone.Caution
        !entry.hasSecret -> "Needs key" to Tone.Caution
        health is LocalMcpHealth.Running -> "Running" to Tone.Live
        health is LocalMcpHealth.Starting -> "Starting" to Tone.Brand
        health is LocalMcpHealth.Failed -> "Failed" to Tone.Critical
        else -> "Stopped" to Tone.Neutral
    }
    ExpandableRow(
        title = c.name,
        subtitle = "Local MCP server · runs in Linux",
        leading = { ConnectorTile(c) },
        badge = badge,
        badgeTone = tone,
        startExpanded = entry.draft,
        expandKey = if (entry.draft) "draft" else "active",
    ) {
        Text(c.description, style = LocusType.rowSupporting)
        SupportingText(
            if (entry.draft) "Approving lets Locus run this command inside its Linux system when a chat uses this server:" else "Command, run in /workspace inside Linux:",
        )
        Text((listOf(local.command) + local.args.map { "'$it'" }).joinToString(" "), style = LocusType.code)
        local.env.forEach { e ->
            val missing = e.secret in entry.missingSecrets
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The variable the server reads, and whether its key is saved; the vault's own id stays out of sight.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(e.name, style = LocusType.code)
                    if (missing) Text("Key not added yet", style = LocusType.supporting, color = Locus.colors.onCautionContainer)
                    else SupportingText("Key saved on this phone")
                }
                TextButton(onClick = { onAddSecret(e.secret) }) { Text(if (missing) "Add" else "Replace") }
            }
        }
        if (!entry.draft) HealthNote(health)
        // Actions wrap to a second line at a large font instead of running off the card.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (entry.draft) {
                OutlinedButton(onClick = onReject) { Text("Reject") }
                Button(onClick = onApprove) { Text("Approve") }
            } else {
                when (health) {
                    // Stopping while it starts cancels the start (or stops it as soon as a chat's start finishes).
                    is LocalMcpHealth.Running, LocalMcpHealth.Starting -> TextButton(onClick = onStop) { Text("Stop") }
                    else -> TextButton(onClick = onStart, enabled = entry.hasSecret) { Text(if (health is LocalMcpHealth.Failed) "Try again" else "Start") }
                }
                TextButton(onClick = onRemove) { Text("Remove", color = Locus.colors.critical) }
            }
        }
    }
}

@Composable
private fun HealthNote(health: LocalMcpHealth?) {
    when (health) {
        is LocalMcpHealth.Running -> Text(
            "Running. It stops after 10 idle minutes and with Locus, and starts again when a chat needs it.",
            style = LocusType.supporting,
        )
        LocalMcpHealth.Starting -> Text("Starting… The first run of an npx or uvx command downloads the package, which can take a minute.", style = LocusType.supporting)
        is LocalMcpHealth.Failed -> {
            Text("Failed: ${health.reason}", style = LocusType.supporting, color = Locus.colors.critical)
            if (health.stderrTail.isNotBlank()) {
                SupportingText("Last output (stderr):")
                Text(health.stderrTail.takeLast(1_200), style = LocusType.code, modifier = Modifier.padding(start = Spacing.xs))
            }
        }
        is LocalMcpHealth.Stopped, null -> SupportingText(
            (health as? LocalMcpHealth.Stopped)?.reason?.let { "$it. " }.orEmpty() + "Starts when a chat first uses it.",
        )
    }
}
