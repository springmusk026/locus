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

    private val headroomCache = HeadroomCache(HEADROOM_INTERVAL_MS)
    @Volatile private var lowMemoryAt = 0L

    init {
        runCatching { watchThermal() }
        runCatching { watchBattery() }
        runCatching { watchNetwork() }
        runCatching { watchMemory() }
    }

    override fun headroom(forecastSeconds: Int): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || power == null) return null
        val seconds = forecastSeconds.coerceIn(0, 60)
        return headroomCache.get(seconds, SystemClock.elapsedRealtime()) {
            runCatching { power.getThermalHeadroom(seconds) }.getOrNull()
        }
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
        val charging = chargingFrom(status, plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
        _state.update { it.copy(batteryPercent = percent, charging = charging) }
    }

    private fun readPowerSave() {
        _state.update { it.copy(powerSave = power?.isPowerSaveMode == true) }
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // The callback below fires at once only when there is a default network: seed from the current
        // one, so a phone offline at launch reads as offline, not as the default Wi-Fi.
        val current = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        _state.update { it.copy(network = networkKindOf(current != null, current?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)) }
        // Fires at once with the current default network, then on every change.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                _state.update { it.copy(network = networkKindOf(connected = true, notMetered = unmetered)) }
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

/**
 * Plugged in, from `ACTION_BATTERY_CHANGED`: `EXTRA_PLUGGED` is non-zero on any power source. `STATUS_FULL`
 * alone isn't charging: a phone unplugged at 100% can still report it. [status] decides only when the
 * broadcast has no plug extra.
 */
internal fun chargingFrom(status: Int, plugged: Int): Boolean =
    if (plugged >= 0) plugged != 0 else status == BatteryManager.BATTERY_STATUS_CHARGING

/** The default network's kind: none when there is no default network. */
internal fun networkKindOf(connected: Boolean, notMetered: Boolean): NetworkKind = when {
    !connected -> NetworkKind.NONE
    notMetered -> NetworkKind.UNMETERED
    else -> NetworkKind.METERED
}

/**
 * `getThermalHeadroom` answers, one per forecast length, each asked again at most every [intervalMs]
 * (the ADPF guide: no more than once every 10 seconds; the API may return NaN when called too often).
 * A forecast for another length is never answered from this one's value.
 */
internal class HeadroomCache(private val intervalMs: Long) {
    private class Entry(val at: Long, val value: Float?)

    private val entries = HashMap<Int, Entry>()

    @Synchronized
    fun get(forecastSeconds: Int, now: Long, read: () -> Float?): Float? {
        entries[forecastSeconds]?.takeIf { now - it.at < intervalMs }?.let { return it.value }
        val value = read()?.takeUnless { it.isNaN() || it <= 0f }
        entries[forecastSeconds] = Entry(now, value)
        return value
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceConditionsModule {
    @Binds
    abstract fun bindDeviceConditions(impl: AndroidDeviceConditions): DeviceConditions
}
