package com.spendroid.watch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the watch's Wireless debugging addresses on the Wi-Fi, as Android Studio does: a watch
 * with Wireless debugging on announces where to connect, and while "Pair new device" is open,
 * where to pair. Saves typing two addresses and mixing up their ports.
 */
internal object WatchFinder {

    const val PAIRING = "_adb-tls-pairing._tcp."
    const val CONNECT = "_adb-tls-connect._tcp."

    /** "192.168.1.20:37099" for the first service of [type] found within [timeoutMs], or null. */
    suspend fun find(context: Context, type: String, timeoutMs: Long = 8_000): String? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        return withTimeoutOrNull(timeoutMs) {
            callbackFlow {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val host = info.host?.hostAddress ?: return
                                // IPv4 only: an IPv6 address needs brackets the fields don't expect.
                                if (':' !in host) trySend("$host:${info.port}")
                            }
                        })
                    }
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
            }.first()
        }
    }
}
