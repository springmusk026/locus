package dev.locus.feature.settings.ondevice

import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.RamCheck
import dev.locus.core.ondevice.gguf.DownloadException
import dev.locus.core.ondevice.gguf.GgufCatalogEntry
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufDownloadText
import dev.locus.core.ondevice.gguf.GgufMemory
import dev.locus.core.ondevice.gguf.GgufOffer
import dev.locus.core.ondevice.gguf.HfError

/*
 * What the on-device model screens show, as plain data and pure functions, so filtering, the fit
 * verdict, download states and the words TalkBack reads are unit-tested without Compose.
 */

/** The model a detail screen shows; [encode] is its route argument. */
sealed interface ModelKey {
    /** A LiteRT-LM catalogue model ([dev.locus.core.ondevice.OnDeviceCatalog]). */
    data class LiteRt(val id: String) : ModelKey
    /** A recommended GGUF file ([dev.locus.core.ondevice.gguf.GgufCatalog]). */
    data class Catalog(val id: String) : ModelKey
    /** A GGUF model on the phone ("gguf:…"). */
    data class Installed(val id: String) : ModelKey
    /** A Hugging Face repo found by search ("owner/name"). */
    data class Repo(val repo: String) : ModelKey

    fun encode(): String = when (this) {
        is LiteRt -> "litert:$id"
        is Catalog -> "catalog:$id"
        is Installed -> "installed:$id"
        is Repo -> "repo:$repo"
    }

    companion object {
        fun parse(value: String): ModelKey? {
            val kind = value.substringBefore(':', "")
            val rest = value.substringAfter(':', "").takeIf { it.isNotBlank() } ?: return null
            return when (kind) {
                "litert" -> LiteRt(rest)
                "catalog" -> Catalog(rest)
                "installed" -> Installed(rest)
                "repo" -> Repo(rest)
                else -> null
            }
        }
    }
}

/** Which runtime runs a model; shown as a badge. */
enum class Engine(val label: String) { LiteRt("LiteRT"), LlamaCpp("llama.cpp") }

/** How a model fits this phone's memory. */
enum class Fit(val label: String, val spoken: String) {
    Fits("Fits this phone", "fits this phone"),
    /** Runs, but may be slow or be stopped by Android: the existing "short on memory" warning. */
    Tight("Tight fit", "tight fit for this phone"),
    /** Can't load at all: refused, as at load time. */
    TooLarge("Too large", "too large for this phone"),
    /** The phone's memory couldn't be read. */
    Unknown("Fit unknown", "fit unknown"),
}

/** The verdict for a GGUF model needing [ramBytes] (null: not known yet). */
fun ggufFit(ramBytes: Long?, memory: DeviceMemory): Fit = when {
    ramBytes == null || memory.totalBytes <= 0 -> Fit.Unknown
    GgufMemory.tooLarge(ramBytes, memory) -> Fit.TooLarge
    GgufMemory.warning("", ramBytes, memory) != null -> Fit.Tight
    else -> Fit.Fits
}

/** The verdict for a LiteRT-LM model: below its memory minimum is a warning, never a refusal ([MemoryGate]). */
fun liteRtFit(check: RamCheck, memory: DeviceMemory): Fit = when {
    memory.totalBytes <= 0 -> Fit.Unknown
    check.warn -> Fit.Tight
    else -> Fit.Fits
}

/** A model that can be downloaded, from either catalogue. */
data class DiscoverItem(
    val key: ModelKey,
    val name: String,
    val publisher: String,
    val description: String,
    val engine: Engine,
    val sizeBytes: Long,
    val ramBytes: Long?,
    val fit: Fit,
    val tools: Boolean,
    val license: String,
    val quant: String?,
    val params: String?,
    /** The sentence shown before a download that doesn't fit well, or null. */
    val warning: String?,
) {
    val small: Boolean get() = sizeBytes < SMALL_BYTES

    companion object {
        const val SMALL_BYTES = 1_000_000_000L

        fun of(m: OnDeviceModel, check: RamCheck, memory: DeviceMemory) = DiscoverItem(
            key = ModelKey.LiteRt(m.id),
            name = m.name,
            publisher = "Google (LiteRT community)",
            description = m.description,
            engine = Engine.LiteRt,
            sizeBytes = m.sizeBytes,
            ramBytes = null,
            fit = liteRtFit(check, memory),
            tools = m.callsTools,
            license = "Apache 2.0",
            quant = null,
            params = null,
            warning = MemoryGate.warning(m.name, check),
        )

        fun of(e: GgufCatalogEntry, memory: DeviceMemory): DiscoverItem {
            val ram = e.ramBytes(memory)
            return DiscoverItem(
                key = ModelKey.Catalog(e.id),
                name = e.name,
                publisher = e.repo.substringBefore('/'),
                description = e.description,
                engine = Engine.LlamaCpp,
                sizeBytes = e.sizeBytes,
                ramBytes = ram,
                fit = ggufFit(ram, memory),
                tools = e.callsTools,
                license = e.licenseName.substringBefore(" ("),
                quant = e.quant,
                params = e.sizeLabel,
                warning = GgufMemory.warning(e.name, ram, memory),
            )
        }
    }
}

/** The Discover filter chips. Fits is on by default: a list of models the phone can't run helps nobody. */
data class DiscoverFilters(
    val fitsOnly: Boolean = true,
    val toolsOnly: Boolean = false,
    val smallOnly: Boolean = false,
    /** Null: both engines. */
    val engine: Engine? = null,
) {
    fun apply(items: List<DiscoverItem>): List<DiscoverItem> = items.filter { i ->
        (!fitsOnly || i.fit == Fit.Fits || i.fit == Fit.Unknown) &&
            (!toolsOnly || i.tools) &&
            (!smallOnly || i.small) &&
            (engine == null || i.engine == engine)
    }

    val any: Boolean get() = fitsOnly || toolsOnly || smallOnly || engine != null
}

/** The first [count] picks that fit, smallest first after the best fits: the empty state's suggestions. */
fun topPicks(items: List<DiscoverItem>, count: Int = 3): List<DiscoverItem> =
    items.filter { it.fit == Fit.Fits || it.fit == Fit.Unknown }.filter { it.tools }.sortedBy { it.sizeBytes }.let { fitting ->
        // Spread across sizes: smallest, middle, largest that fits.
        if (fitting.size <= count) fitting else listOf(fitting.first(), fitting[fitting.size / 2], fitting.last())
    }

/** A download's state as the screens show it, for both engines. */
sealed interface DownloadUi {
    val bytes: Long
    val total: Long?

    data class Waiting(override val bytes: Long, override val total: Long?, val wifiOnly: Boolean) : DownloadUi
    data class Running(override val bytes: Long, override val total: Long?, val bytesPerSecond: Long? = null, val etaSeconds: Long? = null) : DownloadUi
    data class Paused(override val bytes: Long, override val total: Long?) : DownloadUi
    data class Verifying(override val total: Long?) : DownloadUi { override val bytes: Long get() = total ?: 0 }
    data class Failed(override val bytes: Long, override val total: Long?, val message: String, val retryable: Boolean) : DownloadUi
    /** [existing]: the file was already on the phone; nothing new was stored. */
    data class Done(val modelId: String, val sha256: String?, val verified: Boolean, val existing: Boolean = false) : DownloadUi {
        override val bytes: Long get() = 0
        override val total: Long? get() = null
    }

    /** 0..1, or null when the size isn't known. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

fun GgufDownloadState.toUi(): DownloadUi = when (this) {
    is GgufDownloadState.Queued -> DownloadUi.Waiting(bytes, total, wifiOnly)
    is GgufDownloadState.Running -> DownloadUi.Running(bytes, total, bytesPerSecond, etaSeconds)
    is GgufDownloadState.Paused -> DownloadUi.Paused(bytes, total)
    GgufDownloadState.Verifying -> DownloadUi.Verifying(null)
    is GgufDownloadState.Failed -> DownloadUi.Failed(bytes, total, message, retryable)
    is GgufDownloadState.Installed -> DownloadUi.Done(modelId, sha256, verified, existing)
}

/** A LiteRT-LM model's download, or null when there's none to show (not started, or done). [partial]: bytes of a stopped one. */
fun liteRtDownloadUi(state: ModelState, partial: Long, size: Long): DownloadUi? = when (state) {
    // wifiOnly is set while the job waits for an unmetered network; progress reports clear it.
    is ModelState.Downloading -> if (state.wifiOnly) {
        DownloadUi.Waiting(state.bytes, state.total, wifiOnly = true)
    } else {
        DownloadUi.Running(state.bytes, state.total)
    }
    ModelState.Verifying -> DownloadUi.Verifying(size)
    is ModelState.Failed -> DownloadUi.Failed(partial, size, state.message, retryable = true)
    ModelState.NotDownloaded -> if (partial > 0) DownloadUi.Paused(partial, size) else null
    ModelState.Ready -> null
}

/**
 * A download in progress (or stopped, failed or just finished), pinned above the lists. [id] is what
 * pause, resume and cancel take; [key] is the detail it opens, null for a URL download still running.
 */
data class ActiveDownload(val key: ModelKey?, val id: String, val name: String, val engine: Engine, val ui: DownloadUi, val verified: Boolean = true)

/** A model on the phone. */
data class InstalledItem(
    val key: ModelKey,
    /** The on-device model id chats store. */
    val modelId: String,
    val name: String,
    val engine: Engine,
    val sizeBytes: Long,
    val tools: Boolean,
    val state: String,
    val fit: Fit,
    val inUse: Boolean = false,
)

/** "1.3 GB", kept on one line. */
fun gb(bytes: Long): String = "%.1f".format(java.util.Locale.ROOT, bytes / 1e9) + GgufDownloadText.NBSP + "GB"

/**
 * A download's status in two lines, so a figure never breaks at 200% font: how much ("0.8 / 1.9 GB"),
 * then speed and time left ("5.0 MB/s · 4 min left") or what it's waiting for.
 */
fun statusLines(ui: DownloadUi): String = when (ui) {
    is DownloadUi.Running -> listOfNotNull(GgufDownloadText.amount(ui.bytes, ui.total), GgufDownloadText.rate(ui.bytesPerSecond, ui.etaSeconds)).joinToString("\n")
    // Nothing downloaded yet: the size alone, not "0.0 / 2.6 GB".
    is DownloadUi.Waiting -> (if (ui.bytes > 0) GgufDownloadText.amount(ui.bytes, ui.total) else ui.total?.let(::gb) ?: "") +
        (if (ui.bytes > 0 || ui.total != null) "\n" else "") + if (ui.wifiOnly) "Waiting for Wi-Fi" else "Waiting to start"
    is DownloadUi.Paused -> GgufDownloadText.amount(ui.bytes, ui.total) + "\nPaused"
    else -> ""
}

/** "Qwen3.5 2B, 1.3 GB, fits this phone, supports tools, Get": a row read as one item. */
fun spokenRow(name: String, sizeBytes: Long?, fit: Fit?, tools: Boolean?, action: String?): String = listOfNotNull(
    name,
    sizeBytes?.let(::gb)?.replace('\u00A0', ' '),
    fit?.spoken,
    tools?.let { if (it) "supports tools" else "chat only" },
    action,
).joinToString(", ")

/** The step a download announcement is at: every 10 %, so TalkBack isn't flooded. */
fun announceStep(fraction: Float?): Int? = fraction?.let { (it * 10).toInt().coerceIn(0, 10) * 10 }

/** How a Hub call or a check failed, so the screen can say what to do. */
data class Problem(val message: String, val kind: Kind) {
    enum class Kind { Offline, RateLimited, Gated, Other }

    val title: String? get() = when (kind) {
        Kind.Offline -> "Offline"
        Kind.RateLimited -> "Too many requests"
        Kind.Gated -> "Needs a Hugging Face login"
        Kind.Other -> null
    }

    /** What to do about a gated repo: the user can't sign in from Locus yet. */
    val advice: String? get() = if (kind == Kind.Gated) {
        "Locus downloads without an account. Search for the same model from another publisher (unsloth, bartowski, ggml-org often have ungated copies), or download the file on a computer and import it."
    } else null

    companion object {
        fun of(e: Throwable): Problem = when (e) {
            is HfError.Offline -> Problem(e.message.orEmpty(), Kind.Offline)
            is HfError.RateLimited -> Problem(e.message.orEmpty(), Kind.RateLimited)
            is HfError.Gated -> Problem(e.message.orEmpty(), Kind.Gated)
            is DownloadException -> Problem(e.message.orEmpty(), if (e.retryable && e.message?.startsWith("No connection") == true) Kind.Offline else Kind.Other)
            is HfError -> Problem(e.message.orEmpty(), Kind.Other)
            else -> Problem("Something went wrong: ${e.message ?: e::class.simpleName}", Kind.Other)
        }
    }
}

/** A file being looked at before download (a pasted URL, a search result's file): its header read, then confirm. */
sealed interface OfferCheck {
    data object Idle : OfferCheck
    data class Checking(val label: String) : OfferCheck
    data class Ready(val offer: GgufOffer, val warning: String?, val fit: Fit) : OfferCheck
    data class Failed(val label: String, val problem: Problem) : OfferCheck

    companion object {
        fun ready(offer: GgufOffer, memory: DeviceMemory) =
            Ready(offer, offer.ramBytes?.let { GgufMemory.warning(offer.name, it, memory) }, ggufFit(offer.ramBytes, memory))
    }
}

/** What's wrong with a pasted address before it's checked online; null when it can be tried. */
fun urlWarning(url: String): String? {
    val u = url.trim()
    if (u.isEmpty()) return null
    return when {
        u.startsWith("http://", ignoreCase = true) -> "Only https:// addresses: a plain http download could be changed on the way."
        !u.startsWith("https://", ignoreCase = true) -> "Paste a full address starting with https://."
        u.contains(' ') -> "An address can't contain spaces."
        else -> null
    }
}
