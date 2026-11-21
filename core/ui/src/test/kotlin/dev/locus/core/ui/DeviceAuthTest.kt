package dev.locus.core.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import dev.locus.core.model.DeviceAuthOutcome
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The platform side of the prompt that can be checked without a phone (docs/design/app-lock.md). */
class DeviceAuthTest {
    @Test
    fun `Android 11+ allows a strong biometric or the screen lock, never a weak biometric alone`() {
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL, DeviceAuthPlatform.AUTHENTICATORS)
        assertEquals(0x0000000f or 0x00008000, DeviceAuthPlatform.AUTHENTICATORS, "the documented constant values")
    }

    @Test
    fun `prompt errors map to what the lock screen says`() {
        assertEquals(DeviceAuthOutcome.CANCELLED, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
        assertEquals(DeviceAuthOutcome.CANCELLED, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_CANCELED))
        assertEquals(DeviceAuthOutcome.CANCELLED, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT))
        assertEquals(DeviceAuthOutcome.LOCKOUT, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT))
        assertEquals(DeviceAuthOutcome.LOCKOUT_PERMANENT, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT))
        assertEquals(DeviceAuthOutcome.NO_CREDENTIAL, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL))
        assertEquals(DeviceAuthOutcome.FAILED, DeviceAuthPlatform.outcomeForError(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `a cancel says nothing, a lockout says why`() {
        assertNull(lockMessage(DeviceAuthOutcome.CANCELLED))
        assertNull(lockMessage(DeviceAuthOutcome.SUCCEEDED))
        assertEquals(R.string.ui_lock_lockout, lockMessage(DeviceAuthOutcome.LOCKOUT))
        assertEquals(R.string.ui_lock_failed, lockMessage(DeviceAuthOutcome.FAILED))
    }
}
