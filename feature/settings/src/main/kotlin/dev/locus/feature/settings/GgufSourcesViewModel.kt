package dev.locus.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.OnDeviceModels
import dev.locus.core.ondevice.gguf.DownloadException
import dev.locus.core.ondevice.gguf.GgufCatalog
import dev.locus.core.ondevice.gguf.GgufCatalogEntry
import dev.locus.core.ondevice.gguf.GgufDownload
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufDownloads
import dev.locus.core.ondevice.gguf.GgufMemory
import dev.locus.core.ondevice.gguf.GgufModels
import dev.locus.core.ondevice.gguf.GgufOffer
import dev.locus.core.ondevice.gguf.HfError
import dev.locus.core.ondevice.gguf.HfFile
import dev.locus.core.ondevice.gguf.HfFileRef
import dev.locus.core.ondevice.gguf.HfHub
import dev.locus.core.ondevice.gguf.HfRepo
import dev.locus.core.ondevice.gguf.HfRepoInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Which way of getting a GGUF model is showing. */
enum class GgufSourceTab(val label: String) { Recommended("Recommended"), Search("Search"), Url("URL"), File("File") }

/** How a Hub call or a check failed, so the screen can say what to do. */
data class GgufProblem(val message: String, val kind: Kind) {
    enum class Kind { Offline, RateLimited, Gated, Other }
}

sealed interface GgufSearch {
    data object Idle : GgufSearch
    data class Loading(val query: String) : GgufSearch
    data class Results(val query: String, val repos: List<HfRepo>) : GgufSearch
    data class Failed(val query: String, val problem: GgufProblem) : GgufSearch
}

/** A repo opened from the search, with its .gguf files. */
sealed interface GgufRepoView {
    val repo: String
    data class Loading(override val repo: String) : GgufRepoView
    data class Files(override val repo: String, val info: HfRepoInfo, val files: List<HfFile>) : GgufRepoView
    data class Failed(override val repo: String, val problem: GgufProblem) : GgufRepoView
}

/** A file being looked at before download: its header read, then the confirm step. */
sealed interface GgufCheck {
    data object Idle : GgufCheck
    data class Checking(val label: String) : GgufCheck
    data class Ready(val offer: GgufOffer, val ramWarning: String?, val tooLarge: Boolean) : GgufCheck
    data class Failed(val label: String, val problem: GgufProblem) : GgufCheck
}

/** A recommended model with what this phone says about it, and its download or install. */
data class GgufCatalogRow(
    val entry: GgufCatalogEntry,
    val ramBytes: Long,
    val ramWarning: String?,
    val tooLarge: Boolean,
    val download: GgufDownload?,
    /** The installed model with this file's checksum, if there is one. */
    val installedId: String?,
)

data class GgufSourcesUiState(
    val catalog: List<GgufCatalogRow> = emptyList(),
    /** Downloads started from Search or URL (Recommended ones show in their own row). */
    val downloads: List<GgufDownload> = emptyList(),
    val search: GgufSearch = GgufSearch.Idle,
    val sort: HfHub.Sort = HfHub.Sort.Downloads,
    val repo: GgufRepoView? = null,
    val check: GgufCheck = GgufCheck.Idle,
    /** The model id "Use" last set as the on-device provider's model. */
    val inUse: String? = null,
    val memory: DeviceMemory = DeviceMemory(0, null, 0, false),
)

/**
 * Settings → Models → Get a GGUF model: the recommended list, Hugging Face search, a pasted URL, and
 * downloads (docs/design/llamacpp.md, "Getting models"). The file import stays on [SettingsViewModel].
 */
@HiltViewModel
class GgufSourcesViewModel @Inject constructor(
    private val downloads: GgufDownloads,
    private val models: GgufModels,
    private val onDevice: OnDeviceModels,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val search = MutableStateFlow<GgufSearch>(GgufSearch.Idle)
    private val sort = MutableStateFlow(HfHub.Sort.Downloads)
    private val repo = MutableStateFlow<GgufRepoView?>(null)
    private val check = MutableStateFlow<GgufCheck>(GgufCheck.Idle)
    private val inUse = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private var repoJob: Job? = null
    private var checkJob: Job? = null

    private val browse = combine(search, sort, repo, check, inUse) { s, o, r, c, u -> Browse(s, o, r, c, u) }

    val uiState: StateFlow<GgufSourcesUiState> = combine(downloads.downloads, models.models, browse) { list, installed, b ->
        val memory = onDevice.deviceMemory()
        val bySha = installed.filter { it.sha256 != null }.associateBy { it.sha256 }
        val catalogIds = GgufCatalog.models.map { it.id }.toSet()
        GgufSourcesUiState(
            catalog = GgufCatalog.models.map { e ->
                val ram = e.ramBytes(memory)
                GgufCatalogRow(
                    entry = e,
                    ramBytes = ram,
                    ramWarning = GgufMemory.warning(e.name, ram, memory),
                    tooLarge = GgufMemory.tooLarge(ram, memory),
                    download = list.firstOrNull { it.offer.catalogId == e.id }?.takeUnless { it.state is GgufDownloadState.Installed },
                    installedId = bySha[e.sha256]?.id,
                )
            },
            downloads = list.filter { d ->
                d.offer.catalogId !in catalogIds &&
                    (d.state !is GgufDownloadState.Installed || installed.any { it.id == (d.state as GgufDownloadState.Installed).modelId })
            },
            search = b.search,
            sort = b.sort,
            repo = b.repo,
            check = b.check,
            inUse = b.inUse,
            memory = memory,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GgufSourcesUiState())

    private data class Browse(val search: GgufSearch, val sort: HfHub.Sort, val repo: GgufRepoView?, val check: GgufCheck, val inUse: String?)

    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            search.value = GgufSearch.Idle
            return
        }
        search.value = GgufSearch.Loading(q)
        searchJob = viewModelScope.launch {
            search.value = try {
                GgufSearch.Results(q, downloads.sources.search(q, sort.value))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                GgufSearch.Failed(q, problem(e))
            }
        }
    }

    fun setSort(value: HfHub.Sort) {
        if (sort.value == value) return
        sort.value = value
        when (val s = search.value) {
            is GgufSearch.Results -> search(s.query)
            is GgufSearch.Failed -> search(s.query)
            else -> Unit
        }
    }

    fun openRepo(id: String) {
        repoJob?.cancel()
        repo.value = GgufRepoView.Loading(id)
        repoJob = viewModelScope.launch {
            repo.value = try {
                val (info, files) = downloads.sources.files(id)
                if (info.gated) GgufRepoView.Failed(id, problem(HfError.Gated(id))) else GgufRepoView.Files(id, info, files)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                GgufRepoView.Failed(id, problem(e))
            }
        }
    }

    fun closeRepo() {
        repoJob?.cancel()
        repo.value = null
    }

    /** A file picked in an opened repo: pinned to the commit the listing came from, then its header read. */
    fun checkFile(info: HfRepoInfo, file: HfFile) {
        val sha = info.sha ?: return
        runCheck(file.fileName) { downloads.sources.offer(HfFileRef(info.id, sha, file.path), onDevice.deviceMemory()) }
    }

    /** A pasted URL: a huggingface.co file link is verified, any other https URL isn't. */
    fun checkUrl(url: String) = runCheck(url.trim().substringAfterLast('/').ifBlank { url }) { downloads.sources.offer(url, onDevice.deviceMemory()) }

    private fun runCheck(label: String, make: suspend () -> GgufOffer) {
        checkJob?.cancel()
        check.value = GgufCheck.Checking(label)
        checkJob = viewModelScope.launch {
            check.value = try {
                val offer = make()
                val memory = onDevice.deviceMemory()
                val ram = offer.ramBytes
                GgufCheck.Ready(
                    offer,
                    ramWarning = ram?.let { GgufMemory.warning(offer.name, it, memory) },
                    tooLarge = ram != null && GgufMemory.tooLarge(ram, memory),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                GgufCheck.Failed(label, problem(e))
            }
        }
    }

    fun dismissCheck() {
        checkJob?.cancel()
        check.value = GgufCheck.Idle
    }

    fun downloadRecommended(entry: GgufCatalogEntry) = viewModelScope.launch {
        downloads.start(downloads.sources.offer(entry, onDevice.deviceMemory()), unmeteredOnly())
    }

    /** Starts the checked offer and closes the confirm step (and the repo sheet). */
    fun downloadChecked() = viewModelScope.launch {
        val ready = check.value as? GgufCheck.Ready ?: return@launch
        if (ready.tooLarge || ready.offer.problem != null) return@launch
        downloads.start(ready.offer, unmeteredOnly())
        check.value = GgufCheck.Idle
        repo.value = null
    }

    fun pause(id: String) = downloads.pause(id)
    fun resume(id: String) = viewModelScope.launch { downloads.resume(id, unmeteredOnly()) }
    fun useAnyNetwork(id: String) = downloads.useAnyNetwork(id)
    fun cancel(id: String) = downloads.cancel(id)
    fun dismiss(id: String) = downloads.dismiss(id)

    /**
     * Makes [modelId] the "On this phone" provider's model, adding that provider when there isn't one,
     * so the next chat on it (and the model sheet's default) uses this model.
     */
    fun use(modelId: String) = viewModelScope.launch {
        val current = settings.current().providers.firstOrNull { it.format == ApiFormat.ON_DEVICE }
        val config = current?.copy(model = modelId)
            ?: ProviderConfig(UUID.randomUUID().toString(), "On this phone", ApiFormat.ON_DEVICE, "", modelId, requiresKey = false)
        settings.saveProvider(config, apiKey = null)
        inUse.value = modelId
    }

    private suspend fun unmeteredOnly() = settings.current().power.downloadsUnmeteredOnly

    private fun problem(e: Exception): GgufProblem = when (e) {
        is HfError.Offline -> GgufProblem(e.message.orEmpty(), GgufProblem.Kind.Offline)
        is HfError.RateLimited -> GgufProblem(e.message.orEmpty(), GgufProblem.Kind.RateLimited)
        is HfError.Gated -> GgufProblem(e.message.orEmpty(), GgufProblem.Kind.Gated)
        is DownloadException -> GgufProblem(
            e.message.orEmpty(),
            if (e.retryable && e.message?.startsWith("No connection") == true) GgufProblem.Kind.Offline else GgufProblem.Kind.Other,
        )
        is HfError -> GgufProblem(e.message.orEmpty(), GgufProblem.Kind.Other)
        else -> GgufProblem("Something went wrong: ${e.message ?: e::class.simpleName}", GgufProblem.Kind.Other)
    }
}
