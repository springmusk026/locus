package dev.locus.feature.settings.ondevice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceCatalog
import dev.locus.core.ondevice.gguf.GgufCatalog
import dev.locus.core.ondevice.gguf.GgufCatalogEntry
import dev.locus.core.ondevice.gguf.GgufDownload
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufMemory
import dev.locus.core.ondevice.gguf.GgufModel
import dev.locus.core.ondevice.gguf.GgufSources
import dev.locus.core.ondevice.gguf.HfFile
import dev.locus.core.ondevice.gguf.HfFileKind
import dev.locus.core.ondevice.gguf.HfFileRef
import dev.locus.core.ondevice.gguf.HfRepoInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One downloadable file of a model: a quantisation, with its size and how it fits this phone. */
data class QuantOption(
    val path: String,
    val fileName: String,
    val quant: String?,
    val sizeBytes: Long,
    val ramBytes: Long?,
    val fit: Fit,
    val sha256: String?,
    /** Why it can't be downloaded (split, projector, draft head), or null. */
    val problem: String?,
    /** The recommended pick, pre-selected. */
    val recommended: Boolean = false,
) {
    val usable: Boolean get() = problem == null
}

/** What the detail screen shows about the model, before or after it's installed. */
data class ModelInfo(
    val name: String,
    val publisher: String,
    val description: String?,
    val engine: Engine,
    val license: String?,
    val licenseUrl: String?,
    val params: String?,
    val contextTokens: Int?,
    val tools: Boolean?,
    /** Where it's pinned: "unsloth/Qwen3.5-2B-GGUF at f6d5376". */
    val source: String?,
)

/** A model on the phone, as the detail screen shows it. */
data class InstalledInfo(
    val modelId: String,
    val sizeBytes: Long,
    val ramBytes: Long?,
    val architecture: String?,
    val quant: String?,
    val contextTokens: Int?,
    val trainedContext: Int?,
    val sha256: String?,
    val verified: Boolean,
    val source: String?,
    val loadCrashed: Boolean,
    val hasTemplate: Boolean,
)

data class DetailUiState(
    val loading: Boolean = true,
    /** The model couldn't be looked up (offline, gated, not found). */
    val problem: Problem? = null,
    val info: ModelInfo? = null,
    val options: List<QuantOption> = emptyList(),
    /** More files are being listed from the Hub (a catalogue entry's other quantisations). */
    val optionsLoading: Boolean = false,
    val optionsProblem: Problem? = null,
    val selected: String? = null,
    val download: ActiveDownload? = null,
    val installed: InstalledInfo? = null,
    val engine: EngineStatus = EngineStatus(),
    val isDefault: Boolean = false,
    val check: OfferCheck = OfferCheck.Idle,
    val memory: DeviceMemory = DeviceMemory(0, null, 0, false),
    /** A message after an action ("Deleted", "Couldn't unload while replying"). */
    val notice: String? = null,
    /** The model was deleted: the screen goes back. */
    val gone: Boolean = false,
) {
    val selectedOption: QuantOption? get() = options.firstOrNull { it.path == selected }
    /** The verdict for this phone: the selected file's, or the installed model's. */
    val fit: Fit get() = installed?.ramBytes?.let { ggufFit(it, memory) } ?: selectedOption?.fit ?: Fit.Unknown
}

/** The detail of one model, from a catalogue, a search result or the phone ([ModelKey]). */
@HiltViewModel
class OnDeviceDetailViewModel @Inject constructor(private val actions: OnDeviceActions) : ViewModel() {
    private val key = MutableStateFlow<ModelKey?>(null)
    private val remote = MutableStateFlow(Remote())
    private val selected = MutableStateFlow<String?>(null)
    private val check = MutableStateFlow<OfferCheck>(OfferCheck.Idle)
    private val extra = MutableStateFlow(Extra())
    private var loadJob: Job? = null
    private var checkJob: Job? = null

    /** What came from the Hub for this key. */
    private data class Remote(
        val loading: Boolean = false,
        val problem: Problem? = null,
        val repo: HfRepoInfo? = null,
        val files: List<HfFile>? = null,
        val filesLoading: Boolean = false,
        val filesProblem: Problem? = null,
    )

    private data class Extra(val defaultModel: String? = null, val notice: String? = null, val gone: Boolean = false)

    private val local = combine(actions.liteRt.states, actions.engines.status, actions.gguf.models, actions.downloads.downloads) { s, e, g, d -> Local(s, e, g, d) }

    private data class Local(val states: Map<String, ModelState>, val engine: EngineStatus, val gguf: List<GgufModel>, val downloads: List<GgufDownload>)

    val uiState: StateFlow<DetailUiState> = combine(key, remote, local, combine(selected, check, extra, ::Triple)) { k, r, l, (sel, c, x) ->
        if (k == null) return@combine DetailUiState()
        val memory = actions.liteRt.deviceMemory()
        val d = detail(k, r, l, sel, memory)
        d.copy(check = c, isDefault = d.installed != null && d.installed.modelId == x.defaultModel, notice = x.notice, gone = d.gone || x.gone, engine = l.engine, memory = memory)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    /** Shows [value]; called once per screen with its route's key. */
    fun load(value: ModelKey) {
        if (key.value == value) return
        key.value = value
        selected.value = null
        remote.value = Remote()
        refreshDefault()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (value) {
                is ModelKey.Repo -> {
                    remote.value = Remote(loading = true)
                    remote.value = try {
                        val (info, files) = actions.downloads.sources.files(value.repo)
                        if (info.gated) Remote(problem = Problem.of(dev.locus.core.ondevice.gguf.HfError.Gated(value.repo)), repo = info) else Remote(repo = info, files = files)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Remote(problem = Problem.of(e))
                    }
                }
                is ModelKey.Catalog -> {
                    val entry = GgufCatalog.models.firstOrNull { it.id == value.id } ?: return@launch
                    remote.value = Remote(filesLoading = true)
                    remote.value = try {
                        Remote(files = actions.downloads.sources.tree(entry.repo, entry.revision))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Remote(filesProblem = Problem.of(e))
                    }
                }
                else -> Unit
            }
        }
    }

    /** Tries the Hub again after a failure. */
    fun retry() {
        val k = key.value ?: return
        key.value = null
        load(k)
    }

    fun select(path: String) {
        selected.value = path
    }

    private fun refreshDefault() = viewModelScope.launch { extra.value = extra.value.copy(defaultModel = actions.defaultModel()) }

    private fun installedId(): String? = uiState.value.installed?.modelId

    /** Downloads the selected file: a catalogue file directly, anything else after its header is checked. */
    fun download() = viewModelScope.launch {
        val state = uiState.value
        when (val k = key.value) {
            is ModelKey.LiteRt -> actions.get(k)
            is ModelKey.Catalog -> {
                val entry = GgufCatalog.models.firstOrNull { it.id == k.id } ?: return@launch
                val option = state.selectedOption
                if (option == null || option.path == entry.file) actions.get(k) else checkFile(HfFileRef(entry.repo, entry.revision, option.path))
            }
            is ModelKey.Repo -> {
                val repo = remote.value.repo ?: return@launch
                val option = state.selectedOption ?: return@launch
                checkFile(HfFileRef(repo.id, repo.sha ?: return@launch, option.path))
            }
            else -> Unit
        }
    }

    private fun checkFile(ref: HfFileRef) {
        checkJob?.cancel()
        check.value = OfferCheck.Checking(ref.fileName)
        checkJob = viewModelScope.launch {
            check.value = try {
                OfferCheck.ready(actions.downloads.sources.offer(ref, actions.liteRt.deviceMemory()), actions.liteRt.deviceMemory())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                OfferCheck.Failed(ref.fileName, Problem.of(e))
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

    fun pause(d: ActiveDownload) = actions.pause(d)
    fun resume(d: ActiveDownload) = viewModelScope.launch { actions.resume(d) }
    fun cancel(d: ActiveDownload) = actions.cancel(d)
    fun useMobileData(d: ActiveDownload) = actions.useMobileData(d)
    fun dismiss(d: ActiveDownload) = actions.dismiss(d)

    fun newChat(onOpen: (String) -> Unit) = viewModelScope.launch {
        val id = installedId() ?: return@launch
        onOpen(actions.newChat(id))
    }

    fun setDefault() = viewModelScope.launch {
        val id = installedId() ?: return@launch
        actions.setDefault(id)
        extra.value = extra.value.copy(defaultModel = id, notice = "New chats use this model.")
    }

    fun unload() = viewModelScope.launch {
        val ok = actions.unload()
        extra.value = extra.value.copy(notice = if (ok) "Unloaded. Its memory is free." else "It's replying now. Unload when it finishes.")
    }

    fun benchmark() = viewModelScope.launch {
        val id = installedId() ?: return@launch
        runCatching { actions.engines.runBenchmark(id) } // failures show in the engine status
    }

    fun delete() = viewModelScope.launch {
        val id = installedId() ?: return@launch
        if (actions.delete(id)) {
            extra.value = extra.value.copy(gone = key.value is ModelKey.Installed, notice = "Deleted.")
        } else {
            extra.value = extra.value.copy(notice = "It's replying now. Delete it when it finishes.")
        }
    }

    fun clearNotice() {
        extra.value = extra.value.copy(notice = null)
    }

    private fun detail(k: ModelKey, r: Remote, l: Local, sel: String?, memory: DeviceMemory): DetailUiState = when (k) {
        is ModelKey.LiteRt -> liteRtDetail(k, l, memory)
        is ModelKey.Catalog -> GgufCatalog.models.firstOrNull { it.id == k.id }?.let { catalogDetail(it, r, l, sel, memory) } ?: missing()
        is ModelKey.Installed -> l.gguf.firstOrNull { it.id == k.id }?.let { installedDetail(it, l, memory) } ?: missing()
        is ModelKey.Repo -> repoDetail(k, r, l, sel, memory)
    }

    private fun missing() = DetailUiState(loading = false, problem = Problem("This model isn't on the phone any more.", Problem.Kind.Other), gone = true)

    private fun liteRtDetail(k: ModelKey.LiteRt, l: Local, memory: DeviceMemory): DetailUiState {
        val m = OnDeviceCatalog.models.firstOrNull { it.id == k.id } ?: return missing()
        val check = MemoryGate.check(m.minRamGb, memory)
        val state = l.states[m.id] ?: ModelState.NotDownloaded
        val file = m.url.substringAfterLast('/')
        return DetailUiState(
            loading = false,
            info = ModelInfo(m.name, "Google (LiteRT community)", m.description, Engine.LiteRt, "Apache 2.0", "https://ai.google.dev/gemma/docs/gemma_4_license", null, null, true, m.url.substringAfter("huggingface.co/").substringBefore("/resolve")),
            options = listOf(QuantOption(file, file, "LiteRT-LM", m.sizeBytes, null, liteRtFit(check, memory), m.sha256, null, recommended = true)),
            selected = file,
            download = liteRtDownloadUi(state, actions.liteRt.partialBytes(m.id), m.sizeBytes)?.let { ActiveDownload(k, m.id, m.name, Engine.LiteRt, it) },
            installed = if (state == ModelState.Ready) InstalledInfo(m.id, m.sizeBytes, null, null, null, null, null, m.sha256, true, null, false, true) else null,
        )
    }

    private fun catalogDetail(e: GgufCatalogEntry, r: Remote, l: Local, sel: String?, memory: DeviceMemory): DetailUiState {
        val pinned = QuantOption(e.file, e.file, e.quant, e.sizeBytes, e.ramBytes(memory), ggufFit(e.ramBytes(memory), memory), e.sha256, null, recommended = true)
        val others = r.files.orEmpty().filter { it.path != e.file }.map { option(it, e.kvBytesPerToken, e.contextLength, memory) }
        val options = listOf(pinned) + others.sortedWith(compareBy({ !it.usable }, { it.sizeBytes }))
        val installed = l.gguf.firstOrNull { m -> m.sha256 != null && options.any { it.sha256 == m.sha256 } }
        return DetailUiState(
            loading = false,
            info = ModelInfo(e.name, e.repo.substringBefore('/'), e.description, Engine.LlamaCpp, e.licenseName, e.licenseUrl, e.sizeLabel, e.contextLength, e.callsTools, "${e.repo} at ${e.revision.take(7)}"),
            options = options,
            optionsLoading = r.filesLoading,
            optionsProblem = r.filesProblem,
            selected = sel ?: e.file,
            download = ggufDownload(l, options, ModelKey.Catalog(e.id)),
            installed = installed?.let(::installedInfo),
        )
    }

    private fun installedDetail(m: GgufModel, l: Local, memory: DeviceMemory): DetailUiState = DetailUiState(
        loading = false,
        info = ModelInfo(m.name, m.source?.substringBefore('/') ?: "Imported file", null, Engine.LlamaCpp, m.license, null, m.sizeLabel, m.contextTokens, m.callsTools, m.source),
        installed = installedInfo(m),
        download = null,
    )

    private fun repoDetail(k: ModelKey.Repo, r: Remote, l: Local, sel: String?, memory: DeviceMemory): DetailUiState {
        val options = r.files.orEmpty().map { option(it, null, null, memory) }
        val withPick = recommend(options)
        val installed = l.gguf.firstOrNull { m -> m.sha256 != null && options.any { it.sha256 == m.sha256 } }
        return DetailUiState(
            loading = r.loading,
            problem = r.problem,
            info = ModelInfo(repoDisplayName(k.repo), k.repo.substringBefore('/'), null, Engine.LlamaCpp, r.repo?.license, "https://huggingface.co/${k.repo}", null, null, null, r.repo?.sha?.let { "${k.repo} at ${it.take(7)}" }),
            options = withPick,
            selected = sel ?: withPick.firstOrNull { it.recommended }?.path,
            download = ggufDownload(l, options, null),
            installed = installed?.let(::installedInfo),
        )
    }

    private fun ggufDownload(l: Local, options: List<QuantOption>, key: ModelKey?): ActiveDownload? {
        val shas = options.mapNotNull { it.sha256 }.toSet()
        val d = l.downloads.firstOrNull { it.offer.sha256 in shas } ?: return null
        if (d.state is GgufDownloadState.Installed) return null // shown as installed instead
        return ActiveDownload(key, d.id, d.offer.name, Engine.LlamaCpp, d.state.toUi(), d.offer.verified)
    }

    private fun installedInfo(m: GgufModel) = InstalledInfo(
        m.id, m.sizeBytes, m.ramBytes, m.architecture, m.quant, m.contextTokens, m.trainedContext, m.sha256, m.verified, m.source, m.loadCrashed, m.hasTemplate,
    )

    companion object {
        /** A Hub file as a choice. The KV cache depends on the model, not the file, so a catalogue entry's applies to all its files. */
        fun option(f: HfFile, kvPerToken: Long?, trained: Int?, memory: DeviceMemory): QuantOption {
            val ram = GgufMemory.estimate(f.sizeBytes, kvPerToken, GgufMemory.chooseContext(trained, f.sizeBytes, kvPerToken, memory))
            val problem = GgufSources.kindProblem(f.kind) ?: if (f.sha256 == null) "Hugging Face publishes no checksum for this file." else null
            return QuantOption(f.path, f.fileName, f.quant ?: kindLabel(f.kind), f.sizeBytes, ram, ggufFit(ram, memory), f.sha256, problem)
        }

        private fun kindLabel(kind: HfFileKind): String? = when (kind) {
            is HfFileKind.Split -> "Part ${kind.part} of ${kind.count}"
            HfFileKind.Projector -> "Vision projector"
            HfFileKind.Draft -> "Draft head"
            HfFileKind.Other -> "Not a model"
            HfFileKind.Model -> null
        }

        /**
         * Marks the pre-selected file: Q4_K_M when it fits (llama.cpp's usual balance of size and
         * quality), else the largest usable file that fits, else the smallest usable one.
         */
        fun recommend(options: List<QuantOption>): List<QuantOption> {
            val usable = options.filter { it.usable }
            val fits = usable.filter { it.fit == Fit.Fits || it.fit == Fit.Unknown }
            val pick = fits.firstOrNull { it.quant.equals("Q4_K_M", ignoreCase = true) }
                ?: fits.maxByOrNull { it.sizeBytes }
                ?: usable.minByOrNull { it.sizeBytes }
            return options.map { if (it.path == pick?.path) it.copy(recommended = true) else it }
                .sortedWith(compareBy({ !it.usable }, { it.sizeBytes }))
        }
    }
}
