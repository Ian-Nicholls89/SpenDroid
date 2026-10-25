package com.spendroid.wear.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.wear.BudgetActivity
import com.spendroid.wear.InstallResultReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/** The watch's own update: its prompt missed or cancelled, kept and installed again from the page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w228dp-h228dp-round-watch-xhdpi")
class WatchUpdateTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun allow() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
    }

    private fun result(status: Int, extra: Intent.() -> Unit = {}) =
        InstallResultReceiver().onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, status).putExtra(InstallResultReceiver.EXTRA_NAME, "0.9.9").apply(extra))

    @Test
    fun `a prompt waiting leaves a notification that opens it, gone once installed`() {
        result(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, Intent("android.content.pm.action.CONFIRM_INSTALL")) }
        assertEquals("SpenDroid 0.9.9 is ready", notifications.single().extras.getString("android.title"))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(notifications.single().contentIntent).savedIntent.action)
        result(PackageInstaller.STATUS_SUCCESS)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `cancelled, it says it's kept to try again`() {
        result(PackageInstaller.STATUS_FAILURE_ABORTED) { putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "cancelled") }
        assertTrue(ShadowToast.getTextOfLatestToast().contains("tap Install to try again"))
    }

    @Test
    fun `the update page installs the kept download without downloading again`() {
        // Kept from before; the address goes nowhere, so a download would fail.
        File(context.filesDir, "updates").apply { mkdirs() }.resolve("wear.apk").writeBytes(ByteArray(64) { it.toByte() })
        context.getSharedPreferences("wear_update", Context.MODE_PRIVATE).edit().putInt("code", 999).commit()
        SampleReading.reading {
            putInt("wearLatestCode", 999); putString("wearLatestName", "0.9.9"); putString("wearApkUrl", "https://example.invalid/wear.apk")
        }.save(context)
        ActivityScenario.launch(BudgetActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            fun settle() = repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.mainClock.advanceTimeBy(150) }
            fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            settle()
            repeat(12) { if (!shows("Update available")) { compose.onRoot().performTouchInput { swipeUp() }; settle() } }
            assertTrue("the update page", shows("Watch app 0.9.9"))
            compose.onAllNodes(hasText("Install") and hasClickAction())[0].performClick()
            // Up to ten seconds: under load the file takes a while to reach the installer.
            repeat(100) { if (context.packageManager.packageInstaller.allSessions.isEmpty()) { settle(); Thread.sleep(100) } }
            assertEquals("handed to Android's installer", 1, context.packageManager.packageInstaller.allSessions.size)
            assertTrue("not downloaded again", !shows("Couldn't download"))
        }
    }
}
