package dev.locus.core.ondevice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A model that runs on the phone. Files come from the LiteRT community on Hugging Face (Apache-2.0). */
data class OnDeviceModel(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /**
     * The total phone memory (GB) this model is meant for. Below it Locus warns before download and
     * load but doesn't block. See [OnDeviceCatalog] for the sources.
     */
    val minRamGb: Int,
)

/**
 * The models Locus offers. Minimum RAM follows Google AI Edge Gallery's allowlist for the same
 * LiteRT-LM files (model_allowlists/1_0_22.json: Gemma-4-E2B-it 8 GB, Gemma-4-E4B-it 12 GB). The
 * model cards' S26 Ultra figures (2K context) agree in shape: E2B uses about 0.7 GB of CPU memory on
 * GPU and 1.7 GB on CPU; E4B 0.7 GB on GPU and 3.3 GB on CPU, on top of what Android and other apps
 * hold. Locus asks for a 16K context, which needs more KV cache than those benchmarks.
 */
object OnDeviceCatalog {
    val models = listOf(
        OnDeviceModel(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            description = "Fast, good for everyday tasks. Best on phones with 8 GB of memory or more.",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            minRamGb = 8,
        ),
        OnDeviceModel(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B",
            description = "Smarter but slower. Best on phones with 12 GB of memory or more.",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            minRamGb = 12,
        ),
    )
}

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

@Singleton
class OnDeviceModels @Inject constructor(@param:ApplicationContext private val context: Context) {

    val catalog = OnDeviceCatalog.models

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    private val _states = MutableStateFlow(catalog.associate { it.id to initialState(it) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    fun model(id: String): OnDeviceModel? = catalog.firstOrNull { it.id == id }

    fun file(model: OnDeviceModel) = File(dir, "${model.id}.litertlm")

    fun isReady(id: String) = _states.value[id] == ModelState.Ready

    /** The phone's memory now: total, advertised (API 34+), available and Android's low-memory flag. */
    fun deviceMemory(): DeviceMemory {
        val info = android.app.ActivityManager.MemoryInfo()
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        am?.getMemoryInfo(info) ?: return DeviceMemory(0, null, 0, false)
        val advertised = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.advertisedMem else null
        return DeviceMemory(info.totalMem, advertised, info.availMem, info.lowMemory)
    }

    /** Whether [id] fits this phone's memory; null for an unknown model. */
    fun ramCheck(id: String): RamCheck? = model(id)?.let { MemoryGate.check(it.minRamGb, deviceMemory()) }

    private fun initialState(model: OnDeviceModel): ModelState {
        val file = file(model)
        return if (file.exists() && file.length() == model.sizeBytes && File(dir, "${model.id}.verified").exists()) ModelState.Ready else ModelState.NotDownloaded
    }

    /**
     * Starts or resumes a download as a WorkManager job with a progress notification, so it keeps
     * going when the user leaves the app. Partial files resume with an HTTP Range request.
     */
    fun download(id: String) {
        if (model(id) == null || isReady(id)) return
        set(id, ModelState.Downloading(File(dir, "$id.part").takeIf { it.exists() }?.length() ?: 0, model(id)!!.sizeBytes))
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Called by [ModelDownloadWorker]; does the actual transfer and verification. */
    internal suspend fun runDownload(id: String, onProgress: suspend (Long, Long) -> Unit) {
        val model = model(id) ?: return
        try {
            val part = File(dir, "${model.id}.part")
            fetch(model, part, onProgress)
            set(id, ModelState.Verifying)
            val actual = sha256(part)
            if (actual != model.sha256) {
                part.delete()
                throw IllegalStateException("Download was corrupted (checksum mismatch). Try again.")
            }
            part.renameTo(file(model))
            File(dir, "${model.id}.verified").writeText(actual)
            set(id, ModelState.Ready)
        } catch (e: kotlinx.coroutines.CancellationException) {
            set(id, ModelState.NotDownloaded)
            throw e
        } catch (e: Exception) {
            set(id, ModelState.Failed(e.message ?: e.toString()))
        }
    }

    fun cancel(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        set(id, ModelState.NotDownloaded)
    }

    private fun workName(id: String) = "model-download-$id"

    fun delete(id: String) {
        cancel(id)
        val model = model(id) ?: return
        file(model).delete()
        File(dir, "${model.id}.part").delete()
        File(dir, "${model.id}.verified").delete()
        set(id, ModelState.NotDownloaded)
    }

    private suspend fun fetch(model: OnDeviceModel, part: File, onProgress: suspend (Long, Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (have >= model.sizeBytes) return
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        val code = connection.responseCode
        if (code == 200 && have > 0) have = 0 // server ignored the range; start over
        if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code).")
        RandomAccessFile(part, "rw").use { out ->
            out.setLength(have)
            out.seek(have)
            connection.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var lastReport = 0L
                while (coroutineContext.isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    have += n
                    if (have - lastReport > 8 * 1024 * 1024) {
                        lastReport = have
                        set(model.id, ModelState.Downloading(have, model.sizeBytes))
                        onProgress(have, model.sizeBytes)
                    }
                }
            }
        }
        if (have != model.sizeBytes) throw IllegalStateException("Download stopped early. Tap Download to resume.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun set(id: String, state: ModelState) = _states.update { it + (id to state) }
}
