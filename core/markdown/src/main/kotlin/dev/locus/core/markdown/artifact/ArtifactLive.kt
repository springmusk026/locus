package dev.locus.core.markdown.artifact

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Who holds the one live artifact WebView. Every other preview in the chat shows its snapshot.
 *
 * A preview that takes the slot evicts the holder: the holder's [claim] callback runs first, while its
 * WebView is still on screen, so it can take a snapshot before it goes. Owners are per card instance
 * (the same artifact can appear twice, inline and full screen), not per artifact.
 */
class ArtifactLiveSlot {
    /** The live owner, observable from composition. */
    var owner: String? by mutableStateOf(null)
        private set

    private var onEvict: (() -> Unit)? = null

    /**
     * Makes [who] live, evicting the current holder (its callback runs first). Returns the evicted
     * owner, or null if the slot was free or already [who]'s.
     */
    fun claim(who: String, onEvicted: () -> Unit): String? {
        val previous = owner
        if (previous == who) {
            onEvict = onEvicted
            return null
        }
        val evict = onEvict
        onEvict = null
        runCatching { evict?.invoke() }
        owner = who
        onEvict = onEvicted
        return previous
    }

    /** Takes the slot only if nobody holds it; true if [who] is now live. */
    fun claimIfFree(who: String, onEvicted: () -> Unit): Boolean {
        if (owner != null && owner != who) return false
        claim(who, onEvicted)
        return true
    }

    /** Gives the slot up, if [who] holds it. The eviction callback doesn't run: the owner is leaving itself. */
    fun release(who: String) {
        if (owner != who) return
        owner = null
        onEvict = null
    }

    fun isLive(who: String) = owner == who
}

/**
 * Snapshots of previews that aren't live, least recently used first out, bounded by total bytes.
 * A single picture bigger than the whole budget isn't kept.
 */
class SnapshotCache<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<String, V>(16, 0.75f, true)

    var totalBytes = 0L
        private set

    @Synchronized
    fun get(key: String): V? = map[key]

    @Synchronized
    fun put(key: String, value: V) {
        val size = sizeOf(value)
        map.remove(key)?.let { totalBytes -= sizeOf(it) }
        if (size > maxBytes) return
        map[key] = value
        totalBytes += size
        val it = map.entries.iterator()
        while (totalBytes > maxBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            totalBytes -= sizeOf(eldest.value)
            it.remove()
        }
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)?.let { totalBytes -= sizeOf(it) }
    }

    @Synchronized
    fun clear() {
        map.clear()
        totalBytes = 0
    }

    @get:Synchronized
    val size: Int get() = map.size

    @Synchronized
    fun keys(): List<String> = map.keys.toList()
}

/** The snapshot of [artifactKey] as drawn in one theme: the same page can look different in dark mode. */
fun snapshotKey(artifactKey: String, dark: Boolean) = "$artifactKey|${if (dark) "dark" else "light"}"
