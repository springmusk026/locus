package dev.locus.core.data.integrity

import androidx.datastore.core.DataStore
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ConnectorSeal
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.datastore.IntegrityAlert
import dev.locus.core.datastore.IntegrityAlerts
import dev.locus.core.datastore.resealSettingsFile
import dev.locus.core.model.AppSettings
import dev.locus.core.model.IntegrityKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool permissions, reviewer settings and connector approvals are sealed with a Keystore key
 * (docs/design/linux-isolation.md, "Sealed permissions"). This checks them at startup, tells the
 * user when something was changed outside Locus, and seals a restored backup's copies.
 */
@Singleton
class PolicyIntegrity @Inject constructor(
    private val settings: DataStore<AppSettings>,
    private val home: AgentHome,
    private val alerts: IntegrityAlerts,
    private val key: IntegrityKey,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Checks both seals now, so a change made while Locus wasn't running is caught (and reported)
     * before anything uses it, and state from before seals is sealed in the run that creates the key.
     */
    fun checkAtStart() {
        scope.launch(Dispatchers.IO) {
            // Create the key in the first run of this version even when nothing needs sealing yet:
            // unsealed state is trusted only in the run that creates it ("first use").
            runCatching { key.createdThisRun() }
            runCatching { settings.data.first() }
            runCatching { home.connectors() }
        }
    }

    /** What to tell the user, or null when nothing failed its check. */
    val message: Flow<String?> = alerts.alerts.map { list -> list.takeIf { it.isNotEmpty() }?.let(::describe) }

    fun dismiss() = alerts.dismiss()

    /** Seals the staged settings and connectors of a backup the user is restoring, with this phone's key. */
    suspend fun sealRestored(settingsFile: File, agentHomeDir: File) {
        resealSettingsFile(settingsFile, key)
        val connectors = File(agentHomeDir, "connectors")
        if (connectors.isDirectory) ConnectorSeal(key).write(connectors)
    }

    companion object {
        fun describe(alerts: List<IntegrityAlert>): String = alerts.joinToString("\n\n") { alert ->
            when (alert) {
                IntegrityAlert.ToolPermissions ->
                    "Your tool permissions were changed outside Locus, so they were reset to safe settings: " +
                        "actions that change something ask you first, and the AI reviewer is off. " +
                        "Deny settings were kept. Check Settings → Permissions."
                is IntegrityAlert.Connectors -> {
                    val one = alert.ids.size == 1
                    "${if (one) "A connector was" else "Connectors were"} added or changed outside Locus " +
                        "(${alert.ids.joinToString()}), so ${if (one) "it was" else "they were"} moved back to drafts. " +
                        "Review ${if (one) "it" else "them"} in Library before approving again."
                }
            }
        }
    }
}
