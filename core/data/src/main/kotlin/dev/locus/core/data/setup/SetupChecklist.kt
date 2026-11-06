package dev.locus.core.data.setup

import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.model.AppSettings
import dev.locus.core.model.SetupItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Quick start left for later, and what of it is still to do (docs/design/onboarding-quick-start.md).
 * Home shows [pending] as a setup card; an item set up anywhere else (Settings, the Workspace, the agent
 * adding a connector) drops out on its own, and [settled] says which stored items to clear for good.
 */
@Singleton
class SetupChecklist @Inject constructor(
    private val settings: SettingsRepository,
    linux: LinuxEnvironment,
    library: LibraryRepository,
) {
    /** The items that need nothing more from the user: set up, or not possible on this phone. */
    private val done: Flow<Set<SetupItem>> = combine(
        settings.settings,
        linux.status,
        settings.integrationKeys.onStart { emit(emptySet()) },
        library.library.map { lib -> lib.connectors.count { !it.draft } }.onStart { emit(0) },
    ) { s, status, keys, connectors -> doneItems(s, status, keys, connectors) }.distinctUntilChanged()

    /** Deferred items still to set up, in [SetupItem] order. */
    val pending: Flow<List<SetupItem>> = combine(settings.settings, done) { s, d -> pendingItems(s.setupLater, d) }.distinctUntilChanged()

    /** Deferred items that are now set up: cleared from settings so a card never comes back for them. */
    val settled: Flow<Set<SetupItem>> = combine(settings.settings, done) { s, d -> s.setupLater intersect d }.distinctUntilChanged()

    /** The user dismissed a card, or the items were set up: their cards go for good. */
    suspend fun clear(items: Set<SetupItem>) {
        if (items.isNotEmpty()) settings.clearSetup(items)
    }

    companion object {
        /**
         * Which items are done. Identity has no live signal (the persona files aren't observed): saving
         * it clears the item directly. Linux that can't run on this phone counts as done, so its card is
         * never shown there.
         */
        fun doneItems(s: AppSettings, linux: LinuxStatus, integrationKeys: Set<String>, activeConnectors: Int): Set<SetupItem> = buildSet {
            if (linux == LinuxStatus.Ready || linux is LinuxStatus.Unavailable) add(SetupItem.Linux)
            if (s.sshHosts.isNotEmpty()) add(SetupItem.Ssh)
            if (integrationKeys.isNotEmpty() || activeConnectors > 0) add(SetupItem.Connectors)
        }

        fun pendingItems(later: Set<SetupItem>, done: Set<SetupItem>): List<SetupItem> =
            SetupItem.entries.filter { it in later && it !in done }
    }
}
