package dev.locus.core.data.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.LockAfter
import dev.locus.core.model.LockSession
import dev.locus.core.model.LockSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the phone has a secure screen lock; an interface so tests can say either. */
fun interface DeviceSecurity {
    fun isDeviceSecure(): Boolean
}

/**
 * `KeyguardManager.isDeviceSecure()` (API 23): true when the user's lock screen is a PIN, pattern or
 * password, not swipe or none (developer.android.com/reference/android/app/KeyguardManager#isDeviceSecure()).
 */
class KeyguardDeviceSecurity @Inject constructor(@param:ApplicationContext private val context: Context) : DeviceSecurity {
    override fun isDeviceSecure(): Boolean = runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppLockModule {
    @Binds abstract fun deviceSecurity(impl: KeyguardDeviceSecurity): DeviceSecurity
}

/**
 * Lock Locus and locked chats (docs/design/app-lock.md): the settings, whether the phone can
 * authenticate the user, and the in-memory session. The session follows the whole app's lifecycle
 * (`ProcessLifecycleOwner`): leaving the screen starts the "Lock after" clock, coming back checks it.
 * The clock is elapsed realtime, so changing the phone's time doesn't move it.
 *
 * It protects what Locus shows on the phone. Runs, scheduled tasks and automation that runs without
 * a screen keep going while it's locked.
 */
@Singleton
class AppLock internal constructor(
    private val settings: SettingsRepository,
    private val device: DeviceSecurity,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Inject constructor(settings: SettingsRepository, device: DeviceSecurity, @ApplicationScope scope: CoroutineScope) :
        this(settings, device, scope, SystemClock::elapsedRealtime)

    private val session = MutableStateFlow(LockSession())
    private val secure = MutableStateFlow(device.isDeviceSecure())
    private val process = UUID.randomUUID().toString()

    /** Null until the settings are read: a screen waits rather than showing content it may have to hide. */
    val status: StateFlow<AppLockStatus?> = combine(
        settings.settings.map { it.appLock }.distinctUntilChanged(),
        secure,
        session,
    ) { lock, available, s -> AppLockStatus(lock, available, s, process) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The status once the settings are read. */
    suspend fun loaded(): AppLockStatus = status.filterNotNull().first()

    /** Follows the app between foreground and background. Call once, on the main thread (Application.onCreate). */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> onForeground()
                    Lifecycle.Event.ON_STOP -> onBackground()
                    else -> Unit
                }
            },
        )
    }

    internal fun onBackground() = session.update { LockSessions.background(it, clock()) }

    internal fun onForeground() {
        refreshDeviceSecure()
        val minutes = status.value?.settings?.lockAfterMinutes ?: LockAfter.DEFAULT_MINUTES
        session.update { LockSessions.foreground(it, clock(), minutes) }
    }

    /** Asks Android again whether the phone has a screen lock (it may have been set or removed meanwhile). */
    fun refreshDeviceSecure() {
        secure.value = device.isDeviceSecure()
    }

    /**
     * Locus is about to open a system screen it expects back from: the credential prompt, the photo or
     * contact picker. A short trip there doesn't lock Locus even when "Lock after" is immediately.
     */
    fun expectReturn() = session.update(LockSessions::expectReturn)

    /** The trip [expectReturn] announced is over (its result arrived). */
    fun returned() = session.update { it.copy(expectingReturn = false) }

    /** The user authenticated: Locus and its locked chats are open until the session locks again. */
    fun unlocked() = session.update { LockSessions.unlock(it.copy(expectingReturn = false)) }

    /**
     * Turns the lock on or off. Turning it on counts as this session's authentication (the user is
     * here, in Settings), so it locks the next time Locus is away; Settings asks for authentication
     * before turning it off.
     */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) session.update(LockSessions::unlock)
        settings.setAppLock { it.copy(enabled = enabled) }
    }

    suspend fun setLockAfter(minutes: Int) = settings.setAppLock { it.copy(lockAfterMinutes = LockAfter.normalized(minutes)) }

    /**
     * Locks or unlocks one chat. Locking it from inside doesn't close it (the screen was let in this
     * session); it asks the next time it's opened after the session locks.
     */
    suspend fun setChatLocked(conversationId: String, locked: Boolean) = settings.setAppLock {
        it.copy(lockedChats = if (locked) it.lockedChats + conversationId else it.lockedChats - conversationId)
    }

    /** A deleted chat leaves the locked set. */
    suspend fun forgetChat(conversationId: String) {
        if (status.value?.settings?.lockedChats?.contains(conversationId) != false) {
            settings.setAppLock { it.copy(lockedChats = it.lockedChats - conversationId) }
        }
    }

    companion object {
        /** For tests: a lock over [settings] with a fake screen lock and clock. */
        fun forTest(settings: SettingsRepository, device: DeviceSecurity, scope: CoroutineScope, clock: () -> Long = { 0L }): AppLock =
            AppLock(settings, device, scope, clock)
    }
}
