package com.spendroid.watch

import com.spendroid.ui.hostPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchInstallerTest {

    /** The watch APK comes from the same release as the phone app, by the tag CI gives it. */
    @Test
    fun `the watch app is fetched from this build's release`() {
        assertEquals(
            "https://github.com/Ian-Nicholls89/SpenDroid/releases/download/v3.1-86/wear-release.apk",
            WatchInstaller.apkUrl("3.1", 86),
        )
    }

    @Test
    fun `an address is read as its host and port`() {
        assertEquals("192.168.1.20" to 41555, hostPort(" 192.168.1.20:41555 "))
        assertNull(hostPort("192.168.1.20"))
        assertNull(hostPort("192.168.1.20:"))
        assertNull(hostPort(":41555"))
        assertNull(hostPort("192.168.1.20:99999"))
    }
}
