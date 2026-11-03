package dev.locus.feature.settings.ondevice

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.gguf.GgufCatalog
import dev.locus.core.ondevice.gguf.GgufDownload
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufImport
import dev.locus.core.ondevice.gguf.GgufModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val loading: Boolean = true,
    val installed: List<InstalledItem> = emptyList(),
    val downloads: List<ActiveDownload> = emptyList(),
    /** Discover after [filters]. */
    val discover: List<DiscoverItem> = emptyList(),
    /** Discover before filtering: how many models the filters hide. */
    val discoverAll: List<DiscoverItem> = emptyList(),
    val filters: DiscoverFilters = DiscoverFilters(),
    val import: GgufImport = GgufImport.Idle,
    val check: OfferCheck = OfferCheck.Idle,
    val memory: DeviceMemory = DeviceMemory(0, null, 0, false),
) {
    val empty: Boolean get() = !loading && installed.isEmpty()
    val totalBytes: Long get() = installed.sumOf { it.sizeBytes }
    val picks: List<DiscoverItem> get() = topPicks(discoverAll)
}

/** Settings → Models → On-device models: what's installed, what's downloading, and what to get. */
@HiltViewModel
class OnDeviceLibraryViewModel @Inject constructor(private val actions: OnDeviceActions) : ViewModel() {
    private val filters = MutableStateFlow(DiscoverFilters())
    private val check = MutableStateFlow<OfferCheck>(OfferCheck.Idle)
    private val defaultModel = MutableStateFlow<String?>(null)
    private var checkJob: Job? = null

    private val local = combine(actions.liteRt.states, actions.engines.status, actions.gguf.models, actions.gguf.import, actions.downloads.downloads) { s, e, g, i, d ->
        Local(s, e, g, i, d)
    }

    val uiState: StateFlow<LibraryUiState> = combine(local, filters, check, defaultModel) { l, f, c, def ->
        val memory = actions.liteRt.deviceMemory()
        val partial = actions.liteRt.catalog.associate { it.id to actions.liteRt.partialBytes(it.id) }
        library(actions.liteRt.catalog, l.states, partial, l.engine, l.gguf, l.downloads, memory, f, def).copy(import = l.import, check = c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private data class Local(
        val states: Map<String, ModelState>,
        val engine: EngineStatus,
        val gguf: List<GgufModel>,
        val import: GgufImport,
        val downloads: List<GgufDownload>,
    )

    init {
        refreshDefault()
    }

    private fun refreshDefault() = viewModelScope.launch { defaultModel.value = actions.defaultModel() }

    fun setFilters(value: DiscoverFilters) {
        filters.value = value
    }

    fun get(item: DiscoverItem) = viewModelScope.launch { actions.get(item.key) }
    fun pause(d: ActiveDownload) = actions.pause(d)
    fun resume(d: ActiveDownload) = viewModelScope.launch { actions.resume(d) }
    fun useMobileData(d: ActiveDownload) = actions.useMobileData(d)
    fun cancel(d: ActiveDownload) = actions.cancel(d)
    fun dismiss(d: ActiveDownload) = actions.dismiss(d)

    fun import(uri: Uri) = actions.gguf.import(uri)
    fun cancelImport() = actions.gguf.cancelImport()
    fun dismissImport() = actions.gguf.dismissImportError()

    /** A pasted URL: read its header, then the confirm sheet ([OfferCheck]). */
    fun checkUrl(url: String) {
        checkJob?.cancel()
        check.value = OfferCheck.Checking(url.trim())
        checkJob = viewModelScope.launch {
            check.value = try {
                OfferCheck.ready(actions.downloads.sources.offer(url, actions.liteRt.deviceMemory()), actions.liteRt.deviceMemory())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                OfferCheck.Failed(url.trim(), Problem.of(e))
            }
        }
    }

    fun dismissCheck() {
        checkJob?.cancel()
        check.value = OfferCheck.Idle
    }

    fun downloadChecked() = viewModelScope.launch {
        val ready = check.value as? OfferCheck.Ready ?: return@launch
        if (ready.fit == Fit.TooLarge || ready.offer.problem != null) return@launch
        actions.get(ready.offer)
        check.value = OfferCheck.Idle
    }

    companion object {
        /** The library from the managers' states; pure, so it's tested without Android. */
        fun library(
            liteRt: List<OnDeviceModel>,
            states: Map<String, ModelState>,
            partial: Map<String, Long>,
            engine: EngineStatus,
            gguf: List<GgufModel>,
            downloads: List<GgufDownload>,
            memory: DeviceMemory,
            filters: DiscoverFilters,
            defaultModel: String?,
        ): LibraryUiState {
            fun stateOf(id: String) = when {
                engine.modelId != id -> "Idle"
                engine.loading -> "Loading"
                engine.busy -> "Replying"
                engine.loaded -> "Loaded"
                else -> "Idle"
            }
            val installed = liteRt.filter { states[it.id] == ModelState.Ready }.map { m ->
                InstalledItem(ModelKey.LiteRt(m.id), m.id, m.name, Engine.LiteRt, m.sizeBytes, tools = true, stateOf(m.id), liteRtFit(MemoryGate.check(m.minRamGb, memory), memory), m.id == defaultModel)
            } + gguf.map { m ->
                InstalledItem(ModelKey.Installed(m.id), m.id, m.name, Engine.LlamaCpp, m.sizeBytes, m.tools, if (m.loadCrashed) "Ran out of memory last time" else stateOf(m.id), ggufFit(m.ramBytes, memory), m.id == defaultModel)
            }
            val active = liteRt.mapNotNull { m ->
                liteRtDownloadUi(states[m.id] ?: ModelState.NotDownloaded, partial[m.id] ?: 0, m.sizeBytes)?.let { ActiveDownload(ModelKey.LiteRt(m.id), m.id, m.name, Engine.LiteRt, it) }
            } + downloads.filter { d ->
                // A finished download shows until dismissed, as long as its model is still there.
                val done = d.state as? GgufDownloadState.Installed
                done == null || gguf.any { it.id == done.modelId }
            }.map { d ->
                val key = (d.state as? GgufDownloadState.Installed)?.let { ModelKey.Installed(it.modelId) } ?: d.offer.catalogId?.let { ModelKey.Catalog(it) }
                ActiveDownload(key, d.id, d.offer.name, Engine.LlamaCpp, d.state.toUi(), d.offer.verified)
            }
            val installedSha = gguf.mapNotNull { it.sha256 }.toSet()
            val busyKeys = active.mapNotNull { it.key }.toSet()
            val all = liteRt.filter { states[it.id] != ModelState.Ready }.map { DiscoverItem.of(it, MemoryGate.check(it.minRamGb, memory), memory) } +
                GgufCatalog.models.filter { it.sha256 !in installedSha }.map { DiscoverItem.of(it, memory) }
            val discover = all.filter { it.key !in busyKeys }
            return LibraryUiState(
                loading = false,
                installed = installed,
                downloads = active,
                discover = filters.apply(discover),
                discoverAll = discover,
                filters = filters,
                memory = memory,
            )
        }
    }
}
