package dev.locus.core.data.device

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.llm.DeviceConditions
import dev.locus.core.model.DeviceState
import dev.locus.core.model.NetworkKind
import dev.locus.core.model.ThermalLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the phone's conditions from the platform and keeps [state] current, for the life of the
 * process (an app singleton, so the listeners are registered once and never leak per screen):
 *
 * - heat: `PowerManager.addThermalStatusListener` (API 29; older phones report NONE);
 * - battery and charging: the sticky `ACTION_BATTERY_CHANGED` broadcast, registered at runtime (it
 *   can't be received through the manifest);
 * - battery saver: `isPowerSaveMode` and `ACTION_POWER_SAVE_MODE_CHANGED` (only sent to registered receivers);
 * - network: `registerDefaultNetworkCallback` and `NET_CAPABILITY_NOT_METERED`;
 * - memory: `onTrimMemory` / `onLowMemory`, cleared once `ActivityManager.MemoryInfo.lowMemory` is false again.
 *
 * Sources: docs/design/battery-thermal.md.
 */
@Singleton
class AndroidDeviceConditions @Inject constructor(@param:ApplicationContext private val context: Context) : DeviceConditions {
    private val power = context.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(DeviceState())
    override val state: StateFlow<DeviceState> = _state.asStateFlow()

    @Volatile private var lastHeadroomAt = 0L
    @Volatile private var lastHeadroom: Float? = null
    @Volatile private var lowMemoryAt = 0L

    init {
        runCatching { watchThermal() }
        runCatching { watchBattery() }
        runCatching { watchNetwork() }
        runCatching { watchMemory() }
    }

    override fun headroom(forecastSeconds: Int): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || power == null) return null
        val now = SystemClock.elapsedRealtime()
        // The ADPF guide: no more than once every 10 seconds (the API may return NaN when called too often).
        if (now - lastHeadroomAt < HEADROOM_INTERVAL_MS) return lastHeadroom
        lastHeadroomAt = now
        val value = runCatching { power.getThermalHeadroom(forecastSeconds.coerceIn(0, 60)) }.getOrNull()
        lastHeadroom = value?.takeUnless { it.isNaN() || it <= 0f }
        return lastHeadroom
    }

    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || power == null) return
        _state.update { it.copy(thermal = ThermalLevel.fromAndroid(power.currentThermalStatus)) }
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { status ->
            _state.update { it.copy(thermal = ThermalLevel.fromAndroid(status)) }
        }
    }

    private fun watchBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent) else readPowerSave()
                // Battery updates come every percent or so: a cheap moment to see whether memory recovered.
                runCatching { recheckMemory() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // System broadcasts only; nothing else may send them, so the receiver isn't exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::readBattery)
        readPowerSave()
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        _state.update { it.copy(batteryPercent = percent, charging = charging) }
    }

    private fun readPowerSave() {
        _state.update { it.copy(powerSave = power?.isPowerSaveMode == true) }
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Fires at once with the current default network, then on every change.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                _state.update { it.copy(network = if (unmetered) NetworkKind.UNMETERED else NetworkKind.METERED) }
            }

            override fun onLost(network: Network) {
                _state.update { it.copy(network = NetworkKind.NONE) }
            }
        })
    }

    private fun watchMemory() {
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // Since API 34 only UI_HIDDEN and BACKGROUND are delivered; BACKGROUND and up mean memory is short.
                @Suppress("DEPRECATION")
                val short = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                if (short) memoryShort() else recheckMemory()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = memoryShort()

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
    }

    private fun memoryShort() {
        lowMemoryAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(lowMemory = true) }
    }

    /** Clears the flag once Android no longer says memory is low and a few minutes have passed. */
    private fun recheckMemory() {
        if (!_state.value.lowMemory || SystemClock.elapsedRealtime() - lowMemoryAt < LOW_MEMORY_HOLD_MS) return
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        if (!info.lowMemory) _state.update { it.copy(lowMemory = false) }
    }

    private companion object {
        const val HEADROOM_INTERVAL_MS = 10_000L
        const val LOW_MEMORY_HOLD_MS = 3 * 60_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceConditionsModule {
    @Binds
    abstract fun bindDeviceConditions(impl: AndroidDeviceConditions): DeviceConditions
}
