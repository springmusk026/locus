package dev.locus.core.designsystem

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.locus.core.model.LocalNetwork

/**
 * Asks for Android 17's local network permission (`ACCESS_LOCAL_NETWORK`, in the Nearby devices
 * group) when the user adds or uses a target on their LAN: an Ollama or LM Studio server, an SSH
 * machine, an MCP server on a private address. A short rationale comes first, then the system
 * prompt. Whatever the answer, the action goes ahead: saving a target never depends on it, and a
 * blocked connection later says how to allow access.
 */
@Stable
class LocalNetworkGate internal constructor(private val context: Context) {
    internal class Pending(val host: String, val action: () -> Unit)

    internal var pending by mutableStateOf<Pending?>(null)

    /** True while the system prompt is showing, so the rationale steps aside. */
    internal var asking by mutableStateOf(false)

    /** Bumped after each answer so readers of [needs] recompose. */
    private var answers by mutableIntStateOf(0)

    /** True below Android 17 or once the user allowed it. Read fresh: they may change it in Settings. */
    val granted: Boolean get() = answers >= 0 && granted(context)

    /** Whether [target] (a URL or host) needs the permission that Locus doesn't have yet. */
    fun needs(target: String): Boolean = LocalNetwork.isLocalTarget(target) && !granted

    /** Runs [action] right away when [target] needs no permission, else after the user has answered. */
    fun run(target: String, action: () -> Unit) {
        if (!needs(target)) return action()
        pending = Pending(LocalNetwork.hostOf(target).orEmpty(), action)
    }

    internal fun finish() {
        val p = pending ?: return
        pending = null
        asking = false
        answers++
        p.action()
    }

    companion object {
        fun granted(context: Context): Boolean =
            Build.VERSION.SDK_INT < LocalNetwork.ENFORCED_FROM_SDK ||
                ContextCompat.checkSelfPermission(context, LocalNetwork.PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}

/** A [LocalNetworkGate] bound to this screen, with its rationale dialog. */
@Composable
fun rememberLocalNetworkGate(): LocalNetworkGate {
    val context = LocalContext.current
    val gate = remember(context) { LocalNetworkGate(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { gate.finish() }
    val pending = gate.pending
    if (pending != null && !gate.asking) {
        AlertDialog(
            onDismissRequest = gate::finish,
            title = { Text("Allow local network access?") },
            text = {
                Text(
                    "${pending.host.ifBlank { "This address" }} is on your local network. Android asks before an app " +
                        "connects to devices like a computer running Ollama, an SSH machine or a home server, and lists " +
                        "it as “Nearby devices”. Locus only uses it to reach the addresses you add.\n\n" +
                        "If Android doesn't ask, allow it in Settings → Apps → Locus → Permissions.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    gate.asking = true
                    launcher.launch(LocalNetwork.PERMISSION)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = gate::finish) { Text("Not now") } },
        )
    }
    return gate
}

/** An inline note under a URL or host field when that target will need local network access. */
@Composable
fun LocalNetworkNote(target: String, gate: LocalNetworkGate, modifier: Modifier = Modifier) {
    if (!gate.needs(target)) return
    Column(modifier.fillMaxWidth()) {
        Text(
            "This is on your local network. Locus needs Android's Nearby devices permission to reach it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { gate.run(target) {} }) { Text("Allow access") }
    }
}
