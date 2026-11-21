package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * Settings → About & privacy → Lock Locus (docs/design/app-lock.md). Stored in the settings DataStore,
 * so a Locus backup carries it; not sealed, because the lock guards against a person holding an
 * unlocked phone, and a tampered file falling back to "off" would be no safer.
 */
@Serializable
data class AppLockSettings(
    /** Locus shows its lock screen instead of any content until the user unlocks it. Off by default. */
    val enabled: Boolean = false,
    /** How long Locus may be in the background before it locks again, in minutes (one of [LockAfter.CHOICES]; 0 is immediately). */
    val lockAfterMinutes: Int = LockAfter.DEFAULT_MINUTES,
    /**
     * Chats that ask for authentication when opened and are never named outside them (lists, search,
     * notifications, shortcuts). Ids only; a deleted chat's id is harmless and dropped when seen.
     */
    val lockedChats: Set<String> = emptySet(),
)

/** The "Lock after" choices. Minutes, so an old or hand-edited value can't fail to parse (an unknown enum would). */
object LockAfter {
    const val DEFAULT_MINUTES = 0
    val CHOICES = listOf(0, 1, 5, 15)

    /** [minutes] as a supported choice: the nearest one. */
    fun normalized(minutes: Int): Int = CHOICES.minBy { kotlin.math.abs(it - minutes) }

    fun millis(minutes: Int): Long = normalized(minutes) * 60_000L

    /**
     * A trip out of Locus that Locus itself started and expects back from (Android's credential screen,
     * the photo picker, a contact picker) doesn't lock it when "immediately" is chosen, if it lasts no
     * longer than this.
     */
    const val EXPECTED_RETURN_GRACE_MS = 2 * 60_000L
}

/**
 * The lock's in-memory session: whether the user has authenticated since Locus last locked. Never
 * saved, so a new process starts locked. [epoch] goes up each time the session locks, so a screen that
 * was let in during one session knows it must ask again in the next.
 */
data class LockSession(
    val unlocked: Boolean = false,
    val epoch: Int = 0,
    /** When Locus left the screen (elapsed-realtime ms), while it's away. */
    val awaySince: Long? = null,
    /** The trip away was one Locus started ([LockSessions.expectReturn]). */
    val expectingReturn: Boolean = false,
)

/** The session's rules. Pure, with the clock passed in, so the timing is tested on the JVM. */
object LockSessions {
    /** Locus left the screen at [now]. A second report while away keeps the first time. */
    fun background(s: LockSession, now: Long): LockSession = if (s.awaySince != null) s else s.copy(awaySince = now)

    /**
     * Locus is back on screen at [now]. Away for [lockAfterMinutes] or longer locks the session (a new
     * [LockSession.epoch]); "immediately" (0) locks on any trip away, except one Locus expected that was
     * shorter than [LockAfter.EXPECTED_RETURN_GRACE_MS]. A clock that went backwards counts as expired.
     */
    fun foreground(s: LockSession, now: Long, lockAfterMinutes: Int): LockSession {
        val since = s.awaySince ?: return s.copy(expectingReturn = false)
        val away = now - since
        val limit = LockAfter.millis(lockAfterMinutes)
        val allowed = if (s.expectingReturn) maxOf(limit, LockAfter.EXPECTED_RETURN_GRACE_MS) else limit
        val expired = away < 0 || away >= allowed
        val back = s.copy(awaySince = null, expectingReturn = false)
        return if (expired) lock(back) else back
    }

    /** The user authenticated. */
    fun unlock(s: LockSession): LockSession = s.copy(unlocked = true)

    /** Ends the session: everything asks again. */
    fun lock(s: LockSession): LockSession = s.copy(unlocked = false, epoch = s.epoch + 1)

    /** Locus is about to open a system screen it expects back from (a credential prompt, a picker). */
    fun expectReturn(s: LockSession): LockSession = s.copy(expectingReturn = true)
}

/**
 * Everything the screens need to know about the lock at one moment. [available] is whether the phone
 * has a secure screen lock (PIN, pattern or password): without one there is nothing to authenticate
 * with, so the lock and locked chats are suspended (and Settings says why) rather than locking the user
 * out of their own chats.
 */
data class AppLockStatus(
    val settings: AppLockSettings = AppLockSettings(),
    val available: Boolean = false,
    val session: LockSession = LockSession(),
    /** Different in every process, so a session key saved before the process died never matches again. */
    val process: String = "",
) {
    val enabled: Boolean get() = settings.enabled

    /** Locus shows its lock screen instead of its content. */
    val appLocked: Boolean get() = settings.enabled && available && !session.unlocked

    /** The lock is on in Settings but the phone has no screen lock, so it can't be enforced. */
    val suspended: Boolean get() = (settings.enabled || settings.lockedChats.isNotEmpty()) && !available

    /** [conversationId] is a locked chat (whether or not it's open right now). */
    fun isLockedChat(conversationId: String?): Boolean = conversationId != null && conversationId in settings.lockedChats

    /** Opening [conversationId] needs authentication first. */
    fun chatNeedsAuth(conversationId: String?): Boolean = isLockedChat(conversationId) && available && !session.unlocked

    /**
     * Inside Locus, [conversationId]'s title and text are replaced ("Locked chat") and it's left out of
     * search: a locked chat until this session is unlocked.
     */
    fun redactInApp(conversationId: String?): Boolean = chatNeedsAuth(conversationId)

    /**
     * Outside Locus (notifications, launcher and Direct Share shortcuts, the widget) a locked chat is
     * never named, unlocked session or not: anyone holding the phone sees those.
     */
    fun redactOutside(conversationId: String?): Boolean = isLockedChat(conversationId)

    /** The key a screen remembers when it's let in, valid until the session locks or the process ends. */
    val sessionKey: String get() = "$process:${session.epoch}"
}

/**
 * Which platform call authenticates the user, by API level, without androidx.biometric
 * (docs/design/app-lock.md):
 * - [AUTHENTICATORS] (Android 11+, API 30): `BiometricPrompt.Builder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`.
 * - [CREDENTIAL_ALLOWED] (Android 10, API 29): `setDeviceCredentialAllowed(true)`, added in API 29 and deprecated in 30.
 * - [CONFIRM_CREDENTIAL_INTENT] (Android 8 to 9, API 26 to 28): `KeyguardManager.createConfirmDeviceCredentialIntent`
 *   started for a result. API 28's BiometricPrompt has no PIN fallback, and requires a negative button.
 */
enum class DeviceAuthPath {
    AUTHENTICATORS,
    CREDENTIAL_ALLOWED,
    CONFIRM_CREDENTIAL_INTENT,
    ;

    companion object {
        fun forSdk(sdk: Int): DeviceAuthPath = when {
            sdk >= 30 -> AUTHENTICATORS
            sdk == 29 -> CREDENTIAL_ALLOWED
            else -> CONFIRM_CREDENTIAL_INTENT
        }
    }
}

/** How an authentication attempt ended, in the terms the lock screen shows. */
enum class DeviceAuthOutcome {
    SUCCEEDED,
    /** The user backed out of the prompt: the lock screen stays, quietly. */
    CANCELLED,
    /** Too many attempts: biometrics are locked out for a while (the PIN still works). */
    LOCKOUT,
    /** Biometrics are off until the PIN, pattern or password is used. */
    LOCKOUT_PERMANENT,
    /** The phone no longer has a screen lock. */
    NO_CREDENTIAL,
    /** Anything else: the prompt couldn't be shown or failed. */
    FAILED,
}
