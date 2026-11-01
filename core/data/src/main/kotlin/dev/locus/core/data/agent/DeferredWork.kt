package dev.locus.core.data.agent

import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.llm.DeviceConditions
import dev.locus.core.model.PauseReason
import dev.locus.core.model.PowerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-urgent background work held while the phone is hot (SEVERE and above) or in battery saver
 * ([PowerPolicy.deferReason]): a chat's title and follow-up suggestions, the lesson-saving reflection
 * after a task, the model catalogue's refresh. Work the user is waiting on never comes here.
 *
 * Held work runs by itself once conditions clear, or at once with [runAnyway]. Each piece is held by
 * [key] (one per chat and kind), so a newer turn's side jobs replace the older ones rather than piling
 * up. Memory only: after a restart there is nothing to catch up on. See docs/design/battery-thermal.md.
 */
@Singleton
class DeferredWork @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val conditions: DeviceConditions,
    private val settings: SettingsRepository,
) {
    /** A held piece of work, for the chat named by [chatId] (null: not tied to a chat). */
    private class Held(val chatId: String?, val block: suspend () -> Unit)

    private val held = LinkedHashMap<String, Held>()

    private val _paused = MutableStateFlow<Map<String, PauseReason>>(emptyMap())

    /** Chats with work on hold, and why; shown as "Paused: phone is hot" with Run anyway. */
    val paused: StateFlow<Map<String, PauseReason>> = _paused.asStateFlow()

    private val reason = MutableStateFlow<PauseReason?>(null)

    init {
        scope.launch {
            combine(conditions.state, settings.settings.map { it.power }) { state, power -> PowerPolicy.deferReason(state, power) }
                .distinctUntilChanged()
                .collect { now ->
                    reason.value = now
                    if (now == null) releaseAll() else synchronized(held) { publish(now) }
                }
        }
    }

    /**
     * Runs [block] now, or holds it under [key] while the phone is hot or in battery saver. Returns
     * true when it was held.
     */
    suspend fun runOrHold(key: String, chatId: String?, block: suspend () -> Unit): Boolean {
        val why = PowerPolicy.deferReason(conditions.state.value, settings.current().power)
        if (why == null) {
            block()
            return false
        }
        synchronized(held) {
            held[key] = Held(chatId, block)
            publish(why)
        }
        return true
    }

    /** Runs [chatId]'s held work now ("Run anyway"). */
    fun runAnyway(chatId: String) = release { it.chatId == chatId }

    /** Drops [chatId]'s held work (the chat was deleted, or a new turn makes it stale). */
    fun drop(chatId: String) {
        synchronized(held) {
            held.entries.removeAll { it.value.chatId == chatId }
            reason.value?.let(::publish) ?: _paused.update { it - chatId }
        }
    }

    private fun releaseAll() = release { true }

    private fun release(which: (Held) -> Boolean) {
        val due = synchronized(held) {
            val picked = held.entries.filter { which(it.value) }
            picked.forEach { held.remove(it.key) }
            reason.value?.let(::publish) ?: _paused.update { emptyMap() }
            picked.map { it.value }
        }
        due.forEach { h -> scope.launch { runCatching { h.block() } } }
    }

    /** Called with [held] locked. */
    private fun publish(why: PauseReason) {
        _paused.value = held.values.mapNotNull { it.chatId }.distinct().associateWith { why }
    }
}
