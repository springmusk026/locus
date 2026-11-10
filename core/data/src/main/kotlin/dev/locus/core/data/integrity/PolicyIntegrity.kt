package dev.locus.core.data.integrity

import androidx.datastore.core.DataStore
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ConnectorSeal
import dev.locus.core.agent.home.InstructionSeal
import dev.locus.core.data.R
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.datastore.IntegrityAlert
import dev.locus.core.datastore.IntegrityAlerts
import dev.locus.core.datastore.resealSettingsFile
import dev.locus.core.linux.EngineText
import dev.locus.core.model.AppSettings
import dev.locus.core.model.IntegrityKey
import dev.locus.core.model.SettingsDestination
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
 * Tool permissions, reviewer settings, connector approvals and what instructs the agent (skills,
 * helpers, persona) are sealed with Keystore keys
 * (docs/design/linux-isolation.md, "Sealed permissions"). This checks them at startup, tells the
 * user when something was changed outside Locus, and seals a restored backup's copies.
 */
@Singleton
class PolicyIntegrity @Inject constructor(
    private val settings: DataStore<AppSettings>,
    private val home: AgentHome,
    private val alerts: IntegrityAlerts,
    private val key: IntegrityKey,
    /** Seals provider addresses, proxies, certificates and SSH machines. */
    private val endpointKey: dev.locus.core.datastore.EndpointIntegrityKey,
    /** Seals active skills, helper definitions and persona files. */
    private val instructionKey: dev.locus.core.datastore.InstructionIntegrityKey,
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
            runCatching { endpointKey.createdThisRun() }
            runCatching { instructionKey.createdThisRun() }
            runCatching { settings.data.first() }
            runCatching { home.connectors() }
            // What instructs the agent: a change made while Locus wasn't running is held for review now.
            runCatching { home.skills() }
            runCatching { home.agents() }
            runCatching { home.personaChanges() }
        }
    }

    /** What to tell the user, one paragraph per alert ([describe]); empty when nothing failed its check. */
    val messages: Flow<List<EngineText>> = alerts.alerts.map { Companion.describe(it) }

    /** The Settings page the notice links to: the first one an alert names ([destination]). */
    val destination: Flow<SettingsDestination?> = alerts.alerts.map { list -> list.firstNotNullOfOrNull { Companion.destination(it) } }

    fun dismiss() = alerts.dismiss()

    /** Seals the staged settings and connectors of a backup the user is restoring, with this phone's key. */
    suspend fun sealRestored(settingsFile: File, agentHomeDir: File) {
        resealSettingsFile(settingsFile, key, endpointKey)
        val connectors = File(agentHomeDir, "connectors")
        if (connectors.isDirectory) ConnectorSeal(key).write(connectors)
        InstructionSeal(instructionKey).writeAll(agentHomeDir)
    }

    companion object {
        private fun names(names: List<String>) = names.joinToString()

        /** What happened for each alert and what Locus did about it, in the app's language. Only shown, never stored. */
        fun describe(alerts: List<IntegrityAlert>): List<EngineText> = alerts.map { alert ->
            when (alert) {
                IntegrityAlert.ToolPermissions -> EngineText.Res(R.string.data_integrity_tool_permissions)
                IntegrityAlert.Endpoints -> EngineText.Res(R.string.data_integrity_endpoints)
                is IntegrityAlert.Connectors -> EngineText.Plural(R.plurals.data_integrity_connectors, alert.ids.size, names(alert.ids))
                is IntegrityAlert.Skills -> EngineText.Plural(R.plurals.data_integrity_skills, alert.names.size, names(alert.names))
                is IntegrityAlert.Helpers -> EngineText.Plural(R.plurals.data_integrity_helpers, alert.names.size, names(alert.names))
                is IntegrityAlert.Persona -> EngineText.Res(R.string.data_integrity_persona, names(alert.files))
            }
        }

        /** The Settings page [alert]'s text sends the user to, or null (Library isn't in Settings). */
        fun destination(alert: IntegrityAlert): SettingsDestination? = when (alert) {
            IntegrityAlert.ToolPermissions -> SettingsDestination.Permissions
            IntegrityAlert.Endpoints -> SettingsDestination.Models
            is IntegrityAlert.Persona -> SettingsDestination.Agent
            is IntegrityAlert.Connectors, is IntegrityAlert.Skills, is IntegrityAlert.Helpers -> null
        }
    }
}
