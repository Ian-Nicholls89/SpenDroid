package com.spendroid.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.5.7: crashes kept on the phone - its own and the watch's - for the user to send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrashLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `crashes are kept, newest first, the last ten, and reported together`() {
        CrashLog.clear(context)
        repeat(12) { i -> CrashLog.record(context, "phone 0.5.7 · thread main", "java.lang.IllegalStateException: crash $i", at = 1_000L + i) }
        CrashLog.record(context, "watch 0.1.11 · thread main", "java.lang.NullPointerException", at = 2_000L)
        val all = CrashLog.all(context)
        assertEquals(10, all.size)
        assertEquals("watch 0.1.11 · thread main", all.first().from)
        val report = CrashLog.report(context)
        assertTrue(report.startsWith("SpenDroid crash report\nDevice: "))
        assertTrue(report.contains("crash 11") && !report.contains("crash 2\n") && report.contains("NullPointerException"))
        CrashLog.clear(context)
        assertTrue(CrashLog.all(context).isEmpty())
    }
}
