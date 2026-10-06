package com.spendroid.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Where the page can be served: the Wi-Fi the phone has joined, and the hotspot it is sharing.
 *
 * The hotspot is not a network Android lists - the phone is the network - so it is found among
 * the phone's own interfaces: up, with a private IPv4 address, and none of the networks Android
 * does list (Wi-Fi joined, mobile data, VPN). Makers name the interface differently - wlan1 and
 * ap0 on Xiaomi's HyperOS and Pixels, swlan0 on Samsung, softap0 on some - so the name only breaks
 * ties; what decides is that it is private and not one of the others.
 */
object LocalAddresses {

    enum class Kind(val label: String) { WIFI("On your Wi-Fi"), HOTSPOT("On your phone's hotspot") }

    data class Address(val kind: Kind, val host: String)

    /** One of the phone's network interfaces, as far as choosing between them goes. */
    data class Iface(val name: String, val up: Boolean, val ipv4: List<String>)

    /** Interfaces that are never the hotspot: links to the carrier, tunnels, Wi-Fi Direct, loopback. */
    private val NEVER = Regex("^(lo|rmnet|rev_rmnet|ccmni|ccemni|ccinet|v4-|tun|ppp|ipsec|ip6tnl|ip6_vti|ip_vti|sit|dummy|ifb|epdg|p2p|aware_|nan|seth|clat|r_rmnet|bond|gre|erspan)")

    /** Names makers give the hotspot, and USB and Bluetooth tethering, best first. */
    private val LIKELY = listOf(
        Regex("^swlan\\d"), // Samsung
        Regex("^ap\\d"), // Pixel, Xiaomi HyperOS, OnePlus, OPPO, realme
        Regex("^softap\\d"), // MediaTek phones
        Regex("^wlan[1-9]"), // Xiaomi, Pixel, Motorola, Huawei - the second Wi-Fi interface
        Regex("^wigig\\d"),
        Regex("^(rndis|usb|ncm)\\d"), // USB tethering
        Regex("^bt-pan"), // Bluetooth tethering
    )

    /**
     * The hotspot's address among [ifaces], or null when there is none. [listed] are the interface
     * names of the networks Android lists - Wi-Fi joined, mobile data, VPN - none of which is it.
     */
    fun hotspot(ifaces: List<Iface>, listed: Set<String>): String? {
        val candidates = ifaces.filter { i ->
            i.up && i.name !in listed && !NEVER.containsMatchIn(i.name) && i.ipv4.any(::isPrivate)
        }
        val ranked = candidates.sortedBy { i -> LIKELY.indexOfFirst { it.containsMatchIn(i.name) }.let { if (it < 0) LIKELY.size else it } }
        return ranked.firstOrNull()?.ipv4?.firstOrNull(::isPrivate)
    }

    /** 10/8, 172.16/12 and 192.168/16: a local network's addresses, never the internet's. */
    fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** Where the page can be served right now: Wi-Fi first, then the hotspot. */
    fun find(context: Context): List<Address> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val listed = networks.mapNotNull { runCatching { cm.getLinkProperties(it)?.interfaceName }.getOrNull() }.toSet()
        val wifi = networks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?.let { n -> cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress }
        val ifaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
                Iface(
                    ni.name,
                    runCatching { ni.isUp }.getOrDefault(false),
                    ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
                )
            }
        }.getOrDefault(emptyList())
        val hotspot = hotspot(ifaces, listed)?.takeIf { it != wifi }
        return listOfNotNull(wifi?.let { Address(Kind.WIFI, it) }, hotspot?.let { Address(Kind.HOTSPOT, it) })
    }
}
