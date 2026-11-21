package dev.locus.core.ui

import dev.locus.core.model.DeviceAuthOutcome
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/** The lock screen in front of Locus and in front of a locked chat, and what it says after an attempt (docs/design/app-lock.md). */
class LockScreenScreenshots : ScreenshotTest() {
    @Test fun app() = snap("lock-app") { LockScreen(LockScreenKind.App, onUnlock = {}) }

    @Test fun appPrompting() = snap("lock-app-prompting") { LockScreen(LockScreenKind.App, onUnlock = {}, busy = true) }

    @Test fun appLockout() = snap("lock-app-lockout") { LockScreen(LockScreenKind.App, onUnlock = {}, outcome = DeviceAuthOutcome.LOCKOUT) }

    @Test fun appLockoutPermanent() = snap("lock-app-lockout-permanent") { LockScreen(LockScreenKind.App, onUnlock = {}, outcome = DeviceAuthOutcome.LOCKOUT_PERMANENT) }

    @Test fun appFailed() = snap("lock-app-failed") { LockScreen(LockScreenKind.App, onUnlock = {}, outcome = DeviceAuthOutcome.FAILED) }

    /** A cancelled prompt says nothing: the button is the way back. */
    @Test fun appCancelled() = snap("lock-app-cancelled") { LockScreen(LockScreenKind.App, onUnlock = {}, outcome = DeviceAuthOutcome.CANCELLED) }

    @Test fun chat() = snap("lock-chat") { LockScreen(LockScreenKind.Chat, onUnlock = {}, onBack = {}) }
}
