package dev.locus.core.data.device

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/** Something only Locus's screen can do for a tool: a permission prompt or a system picker. */
sealed interface PhoneRequest {
    val id: Long

    /** A runtime permission prompt; answered with a Boolean (granted). */
    data class Permission(override val id: Long, val permission: String) : PhoneRequest

    /** A picker started with [intent]; answered with the result's data Uri, or null. */
    data class Pick(override val id: Long, val intent: Intent) : PhoneRequest

    /** The Photo Picker for up to [max] images or videos; answered with the picked Uris. */
    data class Photos(override val id: Long, val max: Int) : PhoneRequest

    /**
     * Locus's own [rationale] first, then Android's prompt for [permissions] together; answered with a
     * Boolean (all granted). Declining the rationale answers false without Android's prompt.
     */
    data class Permissions(override val id: Long, val permissions: List<String>, val rationale: PermissionRationale) : PhoneRequest
}

/**
 * Why Locus asks for a permission, shown before Android's prompt (developer.android.com/training/permissions/requesting:
 * "show an educational UI to the user"). [blockedHint] is shown instead when Android won't show its prompt
 * again (denied twice), with a way to App info.
 */
data class PermissionRationale(val title: String, val message: String, val blockedHint: String)

/**
 * Hands requests from tools (which run in a coroutine, maybe in the service) to Locus's screen, one at
 * a time, and waits for the answer. Only while the screen is in front: from the background nothing is
 * asked, and the tool says so instead (docs/design/android-bridge.md).
 */
@Singleton
class PhoneRequests @Inject constructor() {
    private val _current = MutableStateFlow<PhoneRequest?>(null)

    /** The request the screen should show now, or null. */
    val current: StateFlow<PhoneRequest?> = _current.asStateFlow()

    private val hosts = AtomicInteger(0)
    private val ids = AtomicLong(0)
    private val one = Mutex()
    @Volatile private var waiting: Pair<Long, CompletableDeferred<Any?>>? = null

    /** Whether a request could be shown now; tests replace it. */
    internal var onScreen: () -> Boolean = {
        hosts.get() > 0 && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /** The screen shows requests while attached; call the returned function when it goes. */
    fun attach(): () -> Unit {
        hosts.incrementAndGet()
        return { hosts.decrementAndGet() }
    }

    /** The screen's answer to request [id]. */
    fun answer(id: Long, value: Any?) {
        val w = waiting ?: return
        if (w.first == id) w.second.complete(value)
    }

    /** Shows the request [make] builds and waits for its answer; [NotShown] when Locus isn't in front or nobody answered in time. */
    suspend fun ask(make: (Long) -> PhoneRequest): Any? = one.withLock {
        if (!withContext(Dispatchers.Main) { onScreen() }) return@withLock NotShown
        val request = make(ids.incrementAndGet())
        val answer = CompletableDeferred<Any?>()
        waiting = request.id to answer
        _current.value = request
        try {
            withTimeoutOrNull(5.minutes) { answer.await() } ?: NotShown
        } finally {
            waiting = null
            _current.value = null
        }
    }

    suspend fun permission(permission: String): Boolean? = when (val a = ask { PhoneRequest.Permission(it, permission) }) {
        NotShown -> null
        else -> a == true
    }

    suspend fun pick(intent: Intent): Result<Uri?>? = when (val a = ask { PhoneRequest.Pick(it, intent) }) {
        NotShown -> null
        else -> Result.success(a as? Uri)
    }

    /** [permissions] after [rationale]; null when nobody could be asked, else whether all were granted. */
    suspend fun permissions(permissions: List<String>, rationale: PermissionRationale): Boolean? =
        when (val a = ask { PhoneRequest.Permissions(it, permissions, rationale) }) {
            NotShown -> null
            else -> a == true
        }

    @Suppress("UNCHECKED_CAST")
    suspend fun photos(max: Int): List<Uri>? = when (val a = ask { PhoneRequest.Photos(it, max) }) {
        NotShown -> null
        else -> (a as? List<Uri>).orEmpty()
    }

    /** The answer when the request couldn't be shown. */
    object NotShown
}
