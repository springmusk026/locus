package dev.locus.core.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.locus.core.model.DeviceAuthOutcome
import dev.locus.core.model.DeviceAuthPath

/**
 * Asks the user to prove it's them with the phone's own lock: fingerprint or face (Class 3), or the
 * PIN, pattern or password (docs/design/app-lock.md). Only framework APIs, no androidx.biometric:
 * the platform call for each API level is chosen by [DeviceAuthPath.forSdk].
 */
interface DeviceAuthenticator {
    /** Shows the system prompt; [onResult] is called once, on the main thread. */
    fun authenticate(title: String, subtitle: String?, onResult: (DeviceAuthOutcome) -> Unit)

    /** Dismisses a prompt that's still showing (the screen went away). */
    fun cancel()
}

/** The platform constants and error mapping, apart from the prompt, so they're tested on the JVM. */
object DeviceAuthPlatform {
    /**
     * Android 11+: a Class 3 (strong) biometric or the device credential. Class 2 (weak) face unlock
     * isn't accepted for Locus, as it isn't for the Keystore; such a phone asks for the PIN instead
     * (developer.android.com/reference/android/hardware/biometrics/BiometricManager.Authenticators).
     */
    @RequiresApi(30)
    const val AUTHENTICATORS: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** What an `onAuthenticationError` code means for the lock screen. */
    fun outcomeForError(code: Int): DeviceAuthOutcome = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> DeviceAuthOutcome.CANCELLED
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> DeviceAuthOutcome.LOCKOUT
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> DeviceAuthOutcome.LOCKOUT_PERMANENT
        NO_DEVICE_CREDENTIAL -> DeviceAuthOutcome.NO_CREDENTIAL
        else -> DeviceAuthOutcome.FAILED
    }

    /** `BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL`, added in API 29. */
    private const val NO_DEVICE_CREDENTIAL = 14
}

/**
 * The framework implementation. API 30+ and 29 use `android.hardware.biometrics.BiometricPrompt`
 * with the device credential allowed (so no negative button: the prompt offers "Use PIN" itself);
 * API 26 to 28 start `KeyguardManager.createConfirmDeviceCredentialIntent` through [launchIntent]
 * and report back through [onCredentialResult].
 */
class PlatformDeviceAuthenticator(
    private val activity: Activity,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val launchIntent: (Intent) -> Unit,
) : DeviceAuthenticator {
    private var signal: CancellationSignal? = null
    private var waiting: ((DeviceAuthOutcome) -> Unit)? = null

    override fun authenticate(title: String, subtitle: String?, onResult: (DeviceAuthOutcome) -> Unit) {
        when (DeviceAuthPath.forSdk(sdk)) {
            DeviceAuthPath.CONFIRM_CREDENTIAL_INTENT -> confirmCredential(title, subtitle, onResult)
            DeviceAuthPath.CREDENTIAL_ALLOWED, DeviceAuthPath.AUTHENTICATORS -> if (Build.VERSION.SDK_INT >= 29) prompt(title, subtitle, onResult) else onResult(DeviceAuthOutcome.FAILED)
        }
    }

    /** The credential screen's result (API 26 to 28): RESULT_OK means the user confirmed. False when this one didn't ask. */
    fun onCredentialResult(ok: Boolean): Boolean {
        val callback = waiting ?: return false
        waiting = null
        callback(if (ok) DeviceAuthOutcome.SUCCEEDED else DeviceAuthOutcome.CANCELLED)
        return true
    }

    override fun cancel() {
        signal?.cancel()
        signal = null
    }

    /**
     * Deprecated in API 29 for BiometricPrompt, and the documented way before it: null when the phone
     * has no secure lock screen (developer.android.com/reference/android/app/KeyguardManager#createConfirmDeviceCredentialIntent).
     */
    @Suppress("DEPRECATION")
    private fun confirmCredential(title: String, subtitle: String?, onResult: (DeviceAuthOutcome) -> Unit) {
        val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
            ?: return onResult(DeviceAuthOutcome.NO_CREDENTIAL)
        waiting = onResult
        try {
            launchIntent(intent)
        } catch (_: ActivityNotFoundException) {
            waiting = null
            onResult(DeviceAuthOutcome.FAILED)
        }
    }

    @RequiresApi(29)
    private fun prompt(title: String, subtitle: String?, onResult: (DeviceAuthOutcome) -> Unit) {
        val builder = BiometricPrompt.Builder(activity).setTitle(title)
        if (subtitle != null) builder.setSubtitle(subtitle)
        if (sdk >= 30 && Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(DeviceAuthPlatform.AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val cancel = CancellationSignal().also { signal = it }
        var done = false
        val finish = { outcome: DeviceAuthOutcome ->
            if (!done) {
                done = true
                if (signal === cancel) signal = null
                onResult(outcome)
            }
        }
        try {
            builder.build().authenticate(
                cancel,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(DeviceAuthOutcome.SUCCEEDED)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(DeviceAuthPlatform.outcomeForError(errorCode))

                    // A single unrecognised finger: the prompt stays and says so itself.
                    override fun onAuthenticationFailed() = Unit
                },
            )
        } catch (_: RuntimeException) {
            // IllegalArgumentException from build() or a SecurityException from a missing permission.
            finish(DeviceAuthOutcome.FAILED)
        }
    }
}

/** What a screen holds to ask for authentication: [launch] shows the prompt; [busy] while it's up. */
@Stable
class DeviceAuthLauncher internal constructor() {
    var busy by mutableStateOf(false)
        private set
    internal var authenticator: DeviceAuthenticator? = null
    internal var title: String = ""
    internal var subtitle: String? = null
    internal var beforeLaunch: () -> Unit = {}
    internal var onResult: (DeviceAuthOutcome) -> Unit = {}

    fun launch() {
        if (busy) return
        val auth = authenticator ?: return onResult(DeviceAuthOutcome.FAILED)
        busy = true
        beforeLaunch()
        auth.authenticate(title, subtitle) { outcome ->
            busy = false
            onResult(outcome)
        }
    }
}

/**
 * A [DeviceAuthLauncher] for this screen's activity. [beforeLaunch] runs just before the system
 * prompt opens (the app lock uses it so the trip to Android's credential screen doesn't count as
 * leaving Locus); [onResult] gets every outcome, cancellations included.
 */
@Composable
fun rememberDeviceAuth(
    title: String,
    subtitle: String? = null,
    beforeLaunch: () -> Unit = {},
    onResult: (DeviceAuthOutcome) -> Unit,
): DeviceAuthLauncher {
    val activity = LocalActivity.current
    val result by rememberUpdatedState(onResult)
    val before by rememberUpdatedState(beforeLaunch)
    val launcher = remember { DeviceAuthLauncher() }
    val holder = remember { PlatformHolder() }
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = r.resultCode == Activity.RESULT_OK
        // After the activity was recreated while the credential screen was up, the new authenticator
        // didn't start it; the answer still counts.
        if (holder.platform?.onCredentialResult(ok) != true) launcher.onResult(if (ok) DeviceAuthOutcome.SUCCEEDED else DeviceAuthOutcome.CANCELLED)
    }
    val authenticator = remember(activity) { activity?.let { a -> PlatformDeviceAuthenticator(a) { intent -> credential.launch(intent) } } }
    holder.platform = authenticator
    launcher.authenticator = authenticator
    launcher.title = title
    launcher.subtitle = subtitle
    launcher.beforeLaunch = { before() }
    launcher.onResult = { result(it) }
    DisposableEffect(authenticator) { onDispose { authenticator?.cancel() } }
    return launcher
}

private class PlatformHolder {
    var platform: PlatformDeviceAuthenticator? = null
}
