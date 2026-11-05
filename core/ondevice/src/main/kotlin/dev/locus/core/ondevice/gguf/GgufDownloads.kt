package dev.locus.core.ondevice.gguf

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.ondevice.downloadNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where a GGUF download is. */
sealed interface GgufDownloadState {
    /** Enqueued: waiting for a network ([wifiOnly]: an unmetered one) or for WorkManager to start it. */
    data class Queued(val bytes: Long, val total: Long?, val wifiOnly: Boolean) : GgufDownloadState
    data class Running(val bytes: Long, val total: Long?, val bytesPerSecond: Long?, val etaSeconds: Long?) : GgufDownloadState
    data class Paused(val bytes: Long, val total: Long?) : GgufDownloadState
    /** Computing the SHA-256 and reading the header. */
    data object Verifying : GgufDownloadState
    data class Failed(val message: String, val bytes: Long, val total: Long?, val retryable: Boolean) : GgufDownloadState
    /** [existing]: the file was already on the phone, so nothing new was stored. */
    data class Installed(val modelId: String, val sha256: String, val verified: Boolean, val existing: Boolean = false) : GgufDownloadState
}

data class GgufDownload(val id: String, val offer: GgufOffer, val unmeteredOnly: Boolean, val state: GgufDownloadState)

/**
 * GGUF downloads from the three sources (docs/design/llamacpp.md, "Getting models"). Each runs as a
 * WorkManager job in the foreground with a progress notification, like the LiteRT-LM downloads
 * (`OnDeviceModels`), so it goes on when Locus is in the background, waits for an unmetered network
 * unless the user allows mobile data, and resumes from its `.part` file with an HTTP Range request
 * after Pause, a lost connection or Locus being stopped.
 *
 * Finished, the file's SHA-256 is computed and compared with the Hub's (a mismatch deletes it), its
 * header is checked as an import's is, and it moves into files/gguf/ as a model ([GgufModels.adopt]).
 * Partial files live in files/gguf-downloads/, apart from the import's, which [GgufModels] clears at start.
 */
@Singleton
class GgufDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    network: ModelNetwork,
    private val models: GgufModels,
) {
    private val http = ModelHttp(network)
    val sources = GgufSources(http)
    private val transfer = GgufTransfer(http)
    private val dir = File(context.filesDir, "gguf-downloads").apply { mkdirs() }
    private val index = File(dir, "downloads.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloads = MutableStateFlow(readIndex())
    val downloads: StateFlow<List<GgufDownload>> = _downloads.asStateFlow()

    private val watchers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            // A file an interrupted install left in files/gguf/ becomes a model; a pending download of it is then done.
            runCatching { models.recoverOrphans() }
            _downloads.value.forEach { d ->
                models.withSha(d.offer.sha256)?.let { m ->
                    models.markVerified(m.id, d.offer.source)
                    setState(d.id) { GgufDownloadState.Installed(m.id, m.sha256.orEmpty(), d.offer.verified, existing = true) }
                    part(d.id).delete()
                    return@forEach
                }
                // After a restart, a job WorkManager still holds carries on by itself (and is watched); the others stay paused.
                if (running(d.id)) {
                    setState(d.id) { s -> if (s is GgufDownloadState.Paused) GgufDownloadState.Queued(s.bytes, s.total, d.unmeteredOnly) else s }
                    watch(d.id)
                }
            }
            writeIndex()
        }
    }

    private fun running(id: String): Boolean =
        runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName(id)).get() }.getOrNull().orEmpty()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }

    /**
     * Follows the job of [id] until it ends. A job that ends while the row still says it's working
     * (the worker died, or failed outside [run]) turns the row into a failure with Retry, never a
     * spinner that waits forever.
     */
    private fun watch(id: String) {
        val flow = runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(id)) }.getOrNull() ?: return
        watchers.remove(id)?.cancel()
        watchers[id] = scope.launch {
            val infos = flow.first { list -> list.lastOrNull()?.state?.isFinished == true }
            // Replacing a job reports the old one as cancelled: only settle when nothing runs any more.
            if (!running(id)) settleFinished(id, infos.last().state)
            watchers.remove(id)
        }
    }

    /** The row after its job ended in [state], when the job didn't say how it went itself. */
    fun settleFinished(id: String, state: WorkInfo.State) {
        val d = download(id) ?: return
        val working = d.state is GgufDownloadState.Queued || d.state is GgufDownloadState.Running || d.state is GgufDownloadState.Verifying
        if (!working) return
        setState(id) {
            when (state) {
                WorkInfo.State.CANCELLED -> GgufDownloadState.Paused(partBytes(d), d.offer.sizeBytes)
                else -> GgufDownloadState.Failed("The download stopped unexpectedly. Try again; what's downloaded is kept.", partBytes(d), d.offer.sizeBytes, retryable = true)
            }
        }
    }

    fun download(id: String): GgufDownload? = _downloads.value.firstOrNull { it.id == id }

    /** The download of [offer], if one is in the list. */
    fun forOffer(offer: GgufOffer): GgufDownload? = download(idFor(offer))

    /**
     * Starts [offer]. Refused (as a failed row) when it can't be used ([GgufOffer.problem]) or there
     * isn't room for it. A download of the same file already in the list is resumed instead.
     */
    fun start(offer: GgufOffer, unmeteredOnly: Boolean) {
        val id = idFor(offer)
        // The same file (by checksum) already on the phone: say so, never store a second copy.
        models.withSha(offer.sha256)?.let { m ->
            _downloads.update { list -> list.filterNot { it.id == id } + GgufDownload(id, offer, unmeteredOnly, GgufDownloadState.Installed(m.id, m.sha256.orEmpty(), m.verified, existing = true)) }
            writeIndex()
            return
        }
        // The same file already downloading (another URL for it, or this one): carry that on.
        val same = download(id) ?: offer.sha256?.let { sha -> _downloads.value.firstOrNull { it.offer.sha256.equals(sha, ignoreCase = true) } }
        if (same != null && same.state !is GgufDownloadState.Installed) {
            if (same.state !is GgufDownloadState.Queued && same.state !is GgufDownloadState.Running && same.state !is GgufDownloadState.Verifying) resume(same.id, unmeteredOnly)
            return
        }
        _downloads.update { list -> list.filterNot { it.id == id } + GgufDownload(id, offer, unmeteredOnly, GgufDownloadState.Paused(0, offer.sizeBytes)) }
        writeIndex()
        offer.problem?.let { fail(id, it, retryable = false); return }
        enqueue(id, unmeteredOnly, ExistingWorkPolicy.KEEP)
    }

    fun resume(id: String, unmeteredOnly: Boolean) {
        val d = download(id) ?: return
        if (d.unmeteredOnly != unmeteredOnly) {
            _downloads.update { list -> list.map { if (it.id == id) it.copy(unmeteredOnly = unmeteredOnly) else it } }
            writeIndex()
        }
        enqueue(id, unmeteredOnly, ExistingWorkPolicy.REPLACE)
    }

    /** A download waiting for Wi-Fi, started now on any network: the user chose to spend mobile data. */
    fun useAnyNetwork(id: String) = resume(id, unmeteredOnly = false)

    /** Stops the transfer and keeps what's downloaded; [resume] carries on from there. */
    fun pause(id: String) {
        val d = download(id) ?: return
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        setState(id) { GgufDownloadState.Paused(partBytes(d), d.offer.sizeBytes) }
    }

    /** Stops the transfer, deletes what's downloaded and removes the row. */
    fun cancel(id: String) {
        _downloads.update { list -> list.filterNot { it.id == id } }
        writeIndex()
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        part(id).delete()
    }

    /**
     * Takes a finished row out of the list and returns it, once: the screen that gets it tells the
     * user (a transient message), so a finished download never sits in the list as if it were one.
     */
    @Synchronized
    fun takeFinished(id: String): GgufDownload? {
        val d = download(id)?.takeIf { it.state is GgufDownloadState.Installed } ?: return null
        _downloads.update { list -> list.filterNot { it.id == id } }
        writeIndex()
        return d
    }

    /** Removes a finished or failed row (an installed model stays installed). */
    fun dismiss(id: String) {
        val d = download(id) ?: return
        if (d.state is GgufDownloadState.Installed || d.state is GgufDownloadState.Failed) cancel(id)
    }

    private fun enqueue(id: String, unmeteredOnly: Boolean, policy: ExistingWorkPolicy) {
        val d = download(id) ?: return
        val free = dir.usableSpace
        val need = (d.offer.sizeBytes ?: 0) - partBytes(d) + GgufTransfer.STORAGE_MARGIN
        if (free < need) {
            fail(id, "Not enough free storage: the download needs ${gb(need)} GB more and ${gb(free)} GB is free.", retryable = true)
            return
        }
        setState(id) { GgufDownloadState.Queued(partBytes(d), d.offer.sizeBytes, unmeteredOnly) }
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            policy,
            OneTimeWorkRequestBuilder<GgufDownloadWorker>()
                .setInputData(workDataOf(GgufDownloadWorker.KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(downloadNetwork(unmeteredOnly)).build())
                .build(),
        )
        watch(id)
    }

    /**
     * Called by [GgufDownloadWorker]: the transfer, the checks and the install. Every way it can end
     * (Errors included) leaves the row in a final state; false when it failed.
     */
    suspend fun run(id: String, onProgress: suspend (GgufDownloadState.Running) -> Unit): Boolean {
        val d = download(id) ?: return false
        val part = part(id)
        val rate = TransferRate()
        try {
            val sha = transfer.download(
                GgufTransfer.Target(d.offer.url, d.offer.sizeBytes, d.offer.sha256),
                part,
                freeBytes = { dir.usableSpace },
            ) { bytes, total ->
                val running = rate.sample(System.nanoTime(), bytes, total)
                setState(id) { running }
                onProgress(running)
            }
            setState(id) { GgufDownloadState.Verifying }
            val already = models.withSha(sha) != null // an unverified URL can turn out to be a file already here
            val model = try {
                models.adopt(part, d.offer.fileName, d.offer.source, sha, verified = d.offer.sha256 != null)
            } catch (e: GgufException) {
                part.delete()
                throw DownloadException(if (e.corrupt) "The download isn't a usable GGUF model: ${e.message}" else e.message.orEmpty(), retryable = false)
            }
            setState(id) { GgufDownloadState.Installed(model.id, sha, d.offer.sha256 != null || model.verified, existing = already) }
            writeIndex()
            return true
        } catch (e: CancellationException) {
            if (download(id) != null) setState(id) { s -> if (s is GgufDownloadState.Installed) s else GgufDownloadState.Paused(partBytes(d), d.offer.sizeBytes) }
            throw e
        } catch (e: DownloadException) {
            fail(id, e.message.orEmpty(), e.retryable)
        } catch (e: HfError) {
            fail(id, e.message.orEmpty(), retryable = true)
        } catch (e: Throwable) {
            // Anything else, Errors included (a class that failed to initialise): the row says what broke.
            fail(id, "Locus couldn't finish this download: ${GgufErrorText.scrub(e)}", retryable = true)
        }
        return false
    }

    /** Called by [GgufDownloadWorker] when the job failed outside [run]. */
    fun failed(id: String, e: Throwable) = fail(id, "Locus couldn't finish this download: ${GgufErrorText.scrub(e)}", retryable = true)

    private fun fail(id: String, message: String, retryable: Boolean) {
        val d = download(id) ?: return
        setState(id) { GgufDownloadState.Failed(message, partBytes(d), d.offer.sizeBytes, retryable) }
    }

    private fun setState(id: String, change: (GgufDownloadState) -> GgufDownloadState) =
        _downloads.update { list -> list.map { if (it.id == id) it.copy(state = change(it.state)) else it } }

    private fun part(id: String) = File(dir, "$id.part")

    private fun partBytes(d: GgufDownload): Long = part(d.id).takeIf { it.isFile }?.length() ?: 0

    private fun workName(id: String) = "gguf-download-$id"

    private fun readIndex(): List<GgufDownload> = runCatching {
        if (!index.isFile) return@runCatching emptyList()
        (Json.parseToJsonElement(index.readText()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val offer = GgufOfferJson.decode(o["offer"] as? JsonObject ?: return@mapNotNull null) ?: return@mapNotNull null
            val id = idFor(offer)
            val bytes = part(id).takeIf { it.isFile }?.length() ?: 0
            GgufDownload(id, offer, (o["unmeteredOnly"] as? JsonPrimitive)?.booleanOrNull ?: true, GgufDownloadState.Paused(bytes, offer.sizeBytes))
        }
    }.getOrDefault(emptyList())

    /** Only downloads still to finish are kept across restarts; an installed one is in [GgufModels]. */
    @Synchronized
    private fun writeIndex() {
        val keep = _downloads.value.filter { it.state !is GgufDownloadState.Installed }
        val tmp = File(dir, "downloads.json.tmp")
        tmp.writeText(JsonArray(keep.map { buildJsonObject { put("offer", GgufOfferJson.encode(it.offer)); put("unmeteredOnly", it.unmeteredOnly) } }).toString())
        tmp.renameTo(index)
    }

    private fun gb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / 1e9)

    companion object {
        /** A file-system safe id per download URL: "qwen3.5-2b-q4_k_m-1a2b3c4d". */
        fun idFor(offer: GgufOffer): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(offer.url.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            return GgufImportCheck.slug(offer.fileName).take(48) + "-" + hash
        }
    }
}

/**
 * Bytes per second over the last few seconds, and the time left at that pace, from progress samples.
 * Pure (the clock is passed in) so it's tested on the JVM.
 */
class TransferRate(private val windowNanos: Long = 5_000_000_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun sample(nowNanos: Long, bytes: Long, total: Long?): GgufDownloadState.Running {
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear() // started over
        samples.addLast(nowNanos to bytes)
        while (samples.size > 2 && nowNanos - samples.first().first > windowNanos) samples.removeFirst()
        val (t0, b0) = samples.first()
        val elapsed = nowNanos - t0
        val speed = if (elapsed >= 500_000_000L && bytes > b0) (bytes - b0) * 1_000_000_000L / elapsed else null
        val eta = if (speed != null && speed > 0 && total != null && total >= bytes) (total - bytes) / speed else null
        return GgufDownloadState.Running(bytes, total, speed, eta)
    }
}

/** [GgufOffer] in downloads.json. */
internal object GgufOfferJson {
    fun encode(o: GgufOffer): JsonObject = buildJsonObject {
        put("name", o.name)
        put("fileName", o.fileName)
        put("url", o.url)
        o.sizeBytes?.let { put("sizeBytes", it) }
        o.sha256?.let { put("sha256", it) }
        put("source", o.source)
        o.license?.let { put("license", it) }
        o.catalogId?.let { put("catalogId", it) }
        o.architecture?.let { put("architecture", it) }
        o.quant?.let { put("quant", it) }
        o.sizeLabel?.let { put("sizeLabel", it) }
        o.tools?.let { put("tools", it) }
        o.ramBytes?.let { put("ramBytes", it) }
    }

    fun decode(o: JsonObject): GgufOffer? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        val url = s("url")?.takeIf { it.startsWith("https://") } ?: return null
        return GgufOffer(
            name = s("name") ?: return null,
            fileName = s("fileName") ?: return null,
            url = url,
            sizeBytes = l("sizeBytes"),
            sha256 = s("sha256"),
            source = s("source").orEmpty(),
            license = s("license"),
            catalogId = s("catalogId"),
            architecture = s("architecture"),
            quant = s("quant"),
            sizeLabel = s("sizeLabel"),
            tools = (o["tools"] as? JsonPrimitive)?.booleanOrNull,
            ramBytes = l("ramBytes"),
        )
    }
}

/** A failure's reason as the user sees it: the root cause's class and message, without file paths or URLs. */
object GgufErrorText {
    fun scrub(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root && (root is ExceptionInInitializerError || root.message.isNullOrBlank())) root = root.cause!!
        val name = root::class.simpleName ?: "Error"
        val message = root.message?.lineSequence()?.firstOrNull()?.take(200)
            ?.replace(Regex("""https?://\S+"""), "(address)")
            ?.replace(Regex("""(?:/[\w.@-]+){2,}"""), "(path)")
        return if (message.isNullOrBlank()) name else "$name: $message"
    }
}
