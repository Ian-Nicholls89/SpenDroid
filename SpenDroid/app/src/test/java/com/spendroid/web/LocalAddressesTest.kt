package com.spendroid.web

import com.spendroid.web.LocalAddresses.Iface
import com.spendroid.web.LocalAddresses.hotspot
import com.spendroid.web.LocalAddresses.isPrivate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressesTest {

    private val mobile = Iface("rmnet_data0", true, listOf("10.84.12.7"))
    private val loopback = Iface("lo", true, listOf("127.0.0.1"))

    @Test fun xiaomiHotspotOnWlan1() =
        assertEquals("192.168.43.1", hotspot(listOf(loopback, mobile, Iface("wlan1", true, listOf("192.168.43.1"))), setOf("rmnet_data0")))

    @Test fun hyperOsHotspotOnAp0() =
        assertEquals("10.230.94.1", hotspot(listOf(mobile, Iface("ap0", true, listOf("10.230.94.1"))), setOf("rmnet_data0")))

    @Test fun samsungHotspotOnSwlan0() =
        assertEquals("192.168.131.1", hotspot(listOf(mobile, Iface("swlan0", true, listOf("192.168.131.1"))), setOf("rmnet_data0")))

    @Test fun mediatekCarrierLinkIsNeverTheHotspot() =
        assertEquals("192.168.43.1", hotspot(listOf(Iface("ccmni1", true, listOf("10.1.2.3")), Iface("softap0", true, listOf("192.168.43.1"))), emptySet()))

    @Test fun joinedWifiIsNotTheHotspot() =
        assertNull(hotspot(listOf(Iface("wlan0", true, listOf("192.168.1.20"))), setOf("wlan0")))

    @Test fun wifiAndHotspotTogether() =
        assertEquals("192.168.43.1", hotspot(listOf(Iface("wlan0", true, listOf("192.168.1.20")), Iface("wlan1", true, listOf("192.168.43.1"))), setOf("wlan0")))

    @Test fun tunnelsWifiDirectAndDownInterfacesAreSkipped() = assertNull(
        hotspot(
            listOf(
                Iface("tun0", true, listOf("10.8.0.2")),
                Iface("p2p-wlan0-0", true, listOf("192.168.49.1")),
                Iface("v4-rmnet_data0", true, listOf("192.0.0.4")),
                Iface("wlan1", false, listOf("192.168.43.1")),
            ),
            emptySet(),
        ),
    )

    @Test fun anUnknownNameStillCountsButALikelyOneWins() {
        assertEquals("192.168.7.1", hotspot(listOf(Iface("odd0", true, listOf("192.168.7.1"))), emptySet()))
        assertEquals("192.168.43.1", hotspot(listOf(Iface("odd0", true, listOf("192.168.7.1")), Iface("ap0", true, listOf("192.168.43.1"))), emptySet()))
    }

    @Test fun usbTethering() =
        assertEquals("192.168.42.129", hotspot(listOf(mobile, Iface("rndis0", true, listOf("192.168.42.129"))), setOf("rmnet_data0")))

    @Test fun privateAddresses() {
        assertTrue(isPrivate("10.0.0.1"))
        assertTrue(isPrivate("172.16.5.4"))
        assertTrue(isPrivate("172.31.255.1"))
        assertTrue(isPrivate("192.168.43.1"))
        assertFalse(isPrivate("172.32.0.1"))
        assertFalse(isPrivate("8.8.8.8"))
        assertFalse(isPrivate("192.0.0.4"))
        assertFalse(isPrivate("not.an.ip.x"))
    }
}
