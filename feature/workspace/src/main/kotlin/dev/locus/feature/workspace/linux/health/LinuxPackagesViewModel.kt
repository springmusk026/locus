package dev.locus.feature.workspace.linux.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.model.runCatchingCancellable
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxProbe
import dev.locus.core.linux.LinuxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the packages screen reads and does; replaced in tests. */
interface LinuxPackagesSource {
    suspend fun packages(): List<LinuxInventory.Package>?
    suspend fun upgrades(): List<LinuxInventory.Upgrade>
    suspend fun uninstall(name: String)
}

/**
 * [all] is false for "You installed" (the default) and true for every package; [shown] is the
 * filtered list, computed off the main thread so typing stays smooth with hundreds of packages.
 */
data class LinuxPackagesUiState(
    val status: LinuxStatus,
    val packages: List<LinuxInventory.Package>? = null,
    val shown: List<LinuxInventory.Package> = emptyList(),
    val query: String = "",
    val all: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val upgrades: List<LinuxInventory.Upgrade>? = null,
    val checkingUpgrades: Boolean = false,
    val upgradesError: String? = null,
    val uninstalling: String? = null,
    val message: LinuxHealthUiState.Message? = null,
) {
    val userCount: Int get() = packages?.count { it.userInstalled } ?: 0
}

/**
 * The Linux packages screen (docs/design/linux-health.md#packages), opened from the health screen.
 * [filtering] runs the filter off the main thread; tests pass a test dispatcher.
 */
@HiltViewModel
class LinuxPackagesViewModel(
    private val linux: LinuxEnvironment,
    private val source: LinuxPackagesSource,
    private val filtering: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    @Inject constructor(linux: LinuxEnvironment) : this(
        linux,
        object : LinuxPackagesSource {
            private val probe by lazy { LinuxProbe(linux) }
            override suspend fun packages() = probe.packages()
            override suspend fun upgrades() = probe.upgrades()
            override suspend fun uninstall(name: String) = probe.uninstall(name)
        },
    )

    private val local = MutableStateFlow(LinuxPackagesUiState(linux.status.value))

    /** A package list and the part of it the filter shows, always from the same snapshot. */
    private class Listing(val packages: List<LinuxInventory.Package>?, val shown: List<LinuxInventory.Package>)

    private val listing = local
        .map { Triple(it.packages, it.query, it.all) }
        .distinctUntilChanged()
        .map { (pkgs, query, all) -> Listing(pkgs, pkgs?.let { LinuxInventory.filter(it, query, all) }.orEmpty()) }
        .flowOn(filtering)

    // packages and shown come from the listing together: a reload that lands while the filter is still
    // running keeps the previous list on screen instead of pairing the new list with the old filter.
    val uiState: StateFlow<LinuxPackagesUiState> = combine(local, linux.status, listing) { s, status, l ->
        s.copy(status = status, packages = l.packages, shown = l.shown)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            linux.status.map { it == LinuxStatus.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) load() else local.update { it.copy(packages = null, upgrades = null, error = null, loading = false) }
            }
        }
    }

    fun load() {
        if (linux.status.value != LinuxStatus.Ready) return
        local.update { it.copy(loading = it.packages == null, error = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(source.packages())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            local.update { s ->
                val pkgs = result.getOrNull()
                s.copy(
                    packages = pkgs,
                    loading = false,
                    error = result.exceptionOrNull()?.let { it.message ?: it.toString() }
                        ?: if (pkgs == null) "apk's package database can't be read. Reinstalling Linux fixes it." else null,
                )
            }
        }
    }

    fun setQuery(query: String) = local.update { it.copy(query = query) }

    fun showAll(all: Boolean) = local.update { it.copy(all = all) }

    /** Refreshes apk's index and lists newer versions (network, so only when asked). */
    fun checkUpgrades() {
        if (local.value.checkingUpgrades) return
        local.update { it.copy(checkingUpgrades = true, upgradesError = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { source.upgrades() }
            local.update { it.copy(checkingUpgrades = false, upgrades = result.getOrNull() ?: it.upgrades, upgradesError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }) }
        }
    }

    /** `apk del` for a package the user installed, then reads the list again. */
    fun uninstall(name: String) {
        val pkg = local.value.packages?.firstOrNull { it.name == name } ?: return
        if (local.value.uninstalling != null || !pkg.userInstalled) return
        local.update { it.copy(uninstalling = name) }
        viewModelScope.launch {
            val message = try {
                source.uninstall(name)
                LinuxHealthUiState.Message("Removed $name.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LinuxHealthUiState.Message("Couldn't remove $name: ${e.message ?: e}", problem = true)
            }
            local.update { it.copy(uninstalling = null, message = message) }
            load()
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }
}
