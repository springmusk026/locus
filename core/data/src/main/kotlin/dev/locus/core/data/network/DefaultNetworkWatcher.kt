package dev.locus.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.locus.core.llm.NetworkSwitches

/**
 * Feeds [NetworkSwitches] from the platform: `registerDefaultNetworkCallback` calls `onAvailable` at
 * once with the default network in use, then again whenever another network becomes the default.
 * "When a new network becomes the default, any new connection the app opens uses this network. At
 * some point later, all remaining connections on the previous default network are forcefully
 * terminated" (developer.android.com/develop/connectivity/network-ops/reading-network-state).
 * Registered once for the life of the process.
 */
internal object DefaultNetworkWatcher {
    fun register(context: Context, switches: NetworkSwitches) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = switches.available(network)
            })
        }
    }
}
