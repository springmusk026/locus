package dev.locus.core.ondevice.gguf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.model.GGUF_MODEL_PREFIX
import dev.locus.core.ondevice.OnDeviceModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** An imported GGUF model, as Settings and the model picker show it. Everything here comes from its header. */
data class GgufModel(
    /** "gguf:qwen2.5-1.5b-instruct-q4_k_m": the on-device model id chats store. */
    val id: String,
    val name: String,
    /** The file name it was imported from. */
    val fileName: String,
    val architecture: String,
    val quant: String?,
    /** "1.5B", from general.size_label. */
    val sizeLabel: String?,
    val license: String?,
    val sizeBytes: Long,
    /** The context the model was trained for (`{arch}.context_length`), null when the header doesn't say. */
    val trainedContext: Int?,
    /** The context Locus runs it with ([GgufMemory.chooseContext]). */
    val contextTokens: Int,
    val kvBytesPerToken: Long?,
    /** Its template takes tools ([GgufTemplate.supportsTools]); false is "chat only". */
    val tools: Boolean,
    /** The GGUF carries a chat template; without one llama.cpp falls back to ChatML. */
    val hasTemplate: Boolean,
    val importedAt: Long,
    /** Locus was stopped while loading it last time: Android most likely ran out of memory. */
    val loadCrashed: Boolean = false,
) {
    /** Memory it needs at [contextTokens] ([GgufMemory.estimate]). */
    val ramBytes: Long get() = GgufMemory.estimate(sizeBytes, kvBytesPerToken, contextTokens)
}

/** The import in progress, or how the last one failed. One import runs at a time. */
sealed interface GgufImport {
    data object Idle : GgufImport
    data class Reading(val fileName: String) : GgufImport
    data class Copying(val fileName: String, val bytes: Long, val total: Long?) : GgufImport
    data class Failed(val fileName: String, val message: String) : GgufImport
}

/**
 * GGUF models the user imported through the system file picker. Each is copied into app-private
 * storage (files/gguf/), so it keeps working after the original is moved or its permission lapses,
 * and llama.cpp can memory-map it. There's no download from arbitrary URLs: the LiteRT-LM catalogue's
 * downloads are pinned to known files and SHA-256 sums, and nothing comparable exists for a URL a
 * user pastes (docs/design/llamacpp.md).
 *
 * The header is read from the picked file before anything is copied, so a file that isn't a GGUF, is
 * split, truncated, or of an architecture this llama.cpp doesn't know is refused in a second.
 */
@Singleton
class GgufModels @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val onDevice: OnDeviceModels,
) {
    private val dir = File(context.filesDir, "gguf").apply { mkdirs() }
    private val index = File(dir, "models.json")
    private val loadingMarker = File(dir, "loading")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null

    private val _models = MutableStateFlow(readIndex())
    val models: StateFlow<List<GgufModel>> = _models.asStateFlow()

    private val _import = MutableStateFlow<GgufImport>(GgufImport.Idle)
    val import: StateFlow<GgufImport> = _import.asStateFlow()

    init {
        // A copy cut short by the process dying is useless; the user imports again.
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        // A load that never finished: Android stopped Locus, almost always for memory (docs/design/llamacpp.md).
        if (loadingMarker.exists()) {
            val id = runCatching { loadingMarker.readText().trim() }.getOrDefault("")
            loadingMarker.delete()
            if (id.isNotEmpty()) update(id) { it.copy(loadCrashed = true) }
        }
    }

    fun model(id: String): GgufModel? = _models.value.firstOrNull { it.id == id }

    fun file(model: GgufModel): File = File(dir, model.id.removePrefix(GGUF_MODEL_PREFIX) + ".gguf")

    fun isReady(id: String): Boolean = model(id)?.let { file(it).isFile } == true

    /** The warning before loading [model] on this phone, or null when it fits. */
    fun ramWarning(model: GgufModel): String? = GgufMemory.warning(model.name, model.ramBytes, onDevice.deviceMemory())

    fun tooLarge(model: GgufModel): Boolean = GgufMemory.tooLarge(model.ramBytes, onDevice.deviceMemory())

    /** Imports the file at [uri] (from ACTION_OPEN_DOCUMENT). Progress and errors go to [import]. */
    fun import(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = scope.launch {
            val resolver = context.contentResolver
            val (displayName, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
                    val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                    (n ?: "model.gguf") to s
                }
            } ?: ("model.gguf" to null)
            _import.value = GgufImport.Reading(displayName)
            var part: File? = null
            try {
                val header = resolver.openInputStream(uri)?.use(GgufReader::read) ?: throw GgufException("Couldn't open the file.", corrupt = false)
                GgufImportCheck.problem(header, size, displayName)?.let { throw GgufException(it, corrupt = false) }
                val free = dir.usableSpace
                if (size != null && free < size + STORAGE_MARGIN) {
                    throw GgufException("Not enough free storage: the model needs ${gb(size)} GB and ${gb(free)} GB is free.", corrupt = false)
                }
                val slug = uniqueSlug(GgufImportCheck.slug(displayName))
                val target = File(dir, "$slug.gguf")
                val tmp = File(dir, "$slug.part").also { part = it }
                var copied = 0L
                resolver.openInputStream(uri)?.use { input ->
                    // A new file or nothing: never written through a link or over a file already at the name.
                    java.nio.file.Files.newOutputStream(tmp.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport >= REPORT_EVERY) {
                                lastReport = copied
                                _import.value = GgufImport.Copying(displayName, copied, size)
                            }
                        }
                    }
                } ?: throw GgufException("Couldn't open the file.", corrupt = false)
                if (size != null && copied != size) throw GgufException("The copy stopped early (${gb(copied)} of ${gb(size)} GB). Try again.", corrupt = false)
                header.requiredBytes?.let { need ->
                    if (copied < need) throw GgufException("The file is truncated: its tensors need ${gb(need)} GB and it has ${gb(copied)} GB.")
                }
                if (!tmp.renameTo(target)) throw GgufException("Couldn't save the model in app storage.", corrupt = false)
                part = null
                val model = GgufImportCheck.record(header, "$GGUF_MODEL_PREFIX$slug", displayName, copied, onDevice.deviceMemory(), System.currentTimeMillis())
                _models.update { it + model }
                writeIndex()
                _import.value = GgufImport.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                _import.value = GgufImport.Idle
                throw e
            } catch (e: GgufException) {
                _import.value = GgufImport.Failed(displayName, if (e.corrupt) "This file is damaged or not a GGUF model: ${e.message}" else e.message.orEmpty())
            } catch (e: Exception) {
                _import.value = GgufImport.Failed(displayName, "Couldn't import: ${e.message ?: e::class.simpleName}")
            } finally {
                part?.delete()
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun dismissImportError() {
        if (_import.value is GgufImport.Failed) _import.value = GgufImport.Idle
    }

    /** Deletes the file and the entry. The caller unloads it first ([dev.locus.core.ondevice.OnDeviceEngines.unload]). */
    fun delete(id: String) {
        val model = model(id) ?: return
        file(model).delete()
        _models.update { list -> list.filterNot { it.id == id } }
        writeIndex()
    }

    /** Called around a native load: a marker left behind means the process died while loading [id]. */
    internal fun loadStarted(id: String) = runCatching { loadingMarker.writeText(id) }

    internal fun loadFinished(id: String, ok: Boolean) {
        loadingMarker.delete()
        if (ok) update(id) { if (it.loadCrashed) it.copy(loadCrashed = false) else it }
    }

    /**
     * llama.cpp's own reading of the template, once loaded. When it finds the template can't render
     * tool calls, the model becomes chat only, so the picker and fallbacks stop offering it for tools.
     */
    internal fun confirmTools(id: String, supportsToolCalls: Boolean?) {
        if (supportsToolCalls == false) update(id) { if (it.tools) it.copy(tools = false) else it }
    }

    private fun update(id: String, change: (GgufModel) -> GgufModel) {
        var changed = false
        _models.update { list ->
            list.map { m ->
                if (m.id != id) m else change(m).also { if (it != m) changed = true }
            }
        }
        if (changed) writeIndex()
    }

    private fun uniqueSlug(base: String): String {
        val taken = _models.value.map { it.id.removePrefix(GGUF_MODEL_PREFIX) }.toSet() + (dir.list()?.map { it.substringBeforeLast('.') } ?: emptyList())
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    private fun readIndex(): List<GgufModel> = runCatching {
        if (!index.isFile) return@runCatching emptyList()
        (Json.parseToJsonElement(index.readText()) as JsonArray).mapNotNull { (it as? JsonObject)?.let(GgufIndex::decode) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun writeIndex() {
        val tmp = File(dir, "models.json.tmp")
        tmp.writeText(JsonArray(_models.value.map(GgufIndex::encode)).toString())
        tmp.renameTo(index)
    }

    private fun gb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / 1e9)

    private companion object {
        const val STORAGE_MARGIN = 200L * 1024 * 1024
        const val REPORT_EVERY = 16L * 1024 * 1024
    }
}

/** The import's decisions, pure so they're unit-tested on the JVM. */
object GgufImportCheck {
    /** Why [header] can't be used, or null when it can. [size] is the picked file's size when the provider knows it. */
    fun problem(header: GgufHeader, size: Long?, fileName: String): String? {
        val arch = header.architecture
        return when {
            header.splitCount > 1 -> "This is part of a model split into ${header.splitCount} files. Merge them into one file first (llama.cpp's llama-gguf-split --merge)."
            arch.isEmpty() -> "The file doesn't say which model architecture it is."
            arch == "clip" || fileName.contains("mmproj", ignoreCase = true) ->
                "This is a vision projector (mmproj), not a model. Import the model's own GGUF file."
            !GgufArchitectures.isSupported(arch) -> "Locus's llama.cpp can't run the \"$arch\" architecture."
            !GgufArchitectures.isChat(arch) -> "\"$arch\" models produce embeddings, speech or drafts, not chat replies."
            size != null && header.requiredBytes != null && size < header.requiredBytes ->
                "The file is truncated: its tensors need ${header.requiredBytes} bytes and it has $size."
            else -> null
        }
    }

    /** A file-system and id safe name from the file name: "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf" → "qwen2.5-1.5b-instruct-q4_k_m". */
    fun slug(fileName: String): String {
        val stem = fileName.substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF").lowercase()
        val clean = stem.replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.', '_').take(60)
        return clean.ifEmpty { "model" }
    }

    fun record(header: GgufHeader, id: String, fileName: String, sizeBytes: Long, memory: dev.locus.core.ondevice.DeviceMemory, now: Long): GgufModel {
        val kv = GgufMemory.kvBytesPerToken(header.blockCount, header.headCountKv, header.keyLength, header.valueLength)
        return GgufModel(
            id = id,
            name = header.name ?: fileName.removeSuffix(".gguf"),
            fileName = fileName,
            architecture = header.architecture,
            quant = GgufQuant.name(header.fileType) ?: GgufQuant.fromFileName(fileName),
            sizeLabel = header.sizeLabel,
            license = header.license,
            sizeBytes = sizeBytes,
            trainedContext = header.contextLength,
            contextTokens = GgufMemory.chooseContext(header.contextLength, sizeBytes, kv, memory),
            kvBytesPerToken = kv,
            tools = GgufTemplate.supportsTools(header.chatTemplate),
            hasTemplate = header.chatTemplate != null,
            importedAt = now,
        )
    }
}

/** The index file's encoding (files/gguf/models.json). Unknown fields are ignored, missing ones defaulted. */
internal object GgufIndex {
    fun encode(m: GgufModel): JsonObject = buildJsonObject {
        put("id", m.id)
        put("name", m.name)
        put("fileName", m.fileName)
        put("architecture", m.architecture)
        m.quant?.let { put("quant", it) }
        m.sizeLabel?.let { put("sizeLabel", it) }
        m.license?.let { put("license", it) }
        put("sizeBytes", m.sizeBytes)
        m.trainedContext?.let { put("trainedContext", it) }
        put("contextTokens", m.contextTokens)
        m.kvBytesPerToken?.let { put("kvBytesPerToken", it) }
        put("tools", m.tools)
        put("hasTemplate", m.hasTemplate)
        put("importedAt", m.importedAt)
        put("loadCrashed", m.loadCrashed)
    }

    fun decode(o: JsonObject): GgufModel? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
        val id = s("id")?.takeIf { it.startsWith(GGUF_MODEL_PREFIX) } ?: return null
        return GgufModel(
            id = id,
            name = s("name") ?: id.removePrefix(GGUF_MODEL_PREFIX),
            fileName = s("fileName").orEmpty(),
            architecture = s("architecture").orEmpty(),
            quant = s("quant"),
            sizeLabel = s("sizeLabel"),
            license = s("license"),
            sizeBytes = l("sizeBytes") ?: 0,
            trainedContext = i("trainedContext"),
            contextTokens = i("contextTokens") ?: GgufMemory.CONTEXTS.last(),
            kvBytesPerToken = l("kvBytesPerToken"),
            tools = b("tools") ?: false,
            hasTemplate = b("hasTemplate") ?: false,
            importedAt = l("importedAt") ?: 0,
            loadCrashed = b("loadCrashed") ?: false,
        )
    }
}
