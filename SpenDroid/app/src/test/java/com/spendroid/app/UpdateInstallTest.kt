package com.spendroid.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BuildConfig
import com.spendroid.MainActivity
import com.spendroid.data.ApkInstaller
import com.spendroid.data.InstallResultReceiver
import com.spendroid.data.Stores
import com.spendroid.data.UpdateNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * An update downloaded, its install prompt missed or cancelled: kept, said so in a notification,
 * and installed from there or from Settings without downloading again. Made-up version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdateInstallTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
    private val sessions get() = app.packageManager.packageInstaller.allSessions

    @Before
    fun allow() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
    }

    /** As a download leaves it: the file, and its version. */
    private fun keepDownload(code: Int = BuildConfig.VERSION_CODE + 1, name: String = "9.9") {
        File(app.filesDir, "updates").apply { mkdirs() }.resolve("spendroid.apk").writeBytes(ByteArray(64) { it.toByte() })
        app.getSharedPreferences(Stores.APP_UPDATE, Context.MODE_PRIVATE).edit().putString("name", name).putInt("code", code).commit()
    }

    private fun result(status: Int, extra: Intent.() -> Unit = {}) =
        InstallResultReceiver().onReceive(app, Intent().putExtra(PackageInstaller.EXTRA_STATUS, status).putExtra(InstallResultReceiver.EXTRA_NAME, "9.9").apply(extra))

    private fun title() = notifications.singleOrNull()?.extras?.getString("android.title")

    @Test
    fun `a prompt waiting leaves a notification that opens it`() {
        result(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, Intent("android.content.pm.action.CONFIRM_INSTALL")) }
        assertEquals("SpenDroid 9.9 is ready to install", title())
        val opens = shadowOf(notifications.single().contentIntent).savedIntent
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", opens.action)
    }

    @Test
    fun `cancelled, it says so and its tap goes back to install it`() {
        result(PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals("SpenDroid 9.9 wasn't installed", title())
        assertTrue(shadowOf(notifications.single().contentIntent).savedIntent.getBooleanExtra(UpdateNotice.EXTRA_INSTALL, false))
    }

    @Test
    fun `installed, the notification goes`() {
        result(PackageInstaller.STATUS_FAILURE_ABORTED)
        result(PackageInstaller.STATUS_SUCCESS)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `a kept download is waiting until it's installed, then goes`() {
        keepDownload()
        assertEquals(ApkInstaller.Downloaded("9.9", BuildConfig.VERSION_CODE + 1), ApkInstaller.downloaded(app))
        // Once this app is that version - or newer - it's tidied away at start.
        ApkInstaller.tidy(app, installed = BuildConfig.VERSION_CODE + 1)
        assertNull(ApkInstaller.downloaded(app))
        assertTrue(!File(app.filesDir, "updates/spendroid.apk").exists())
    }

    @Test
    fun `an older download than this app isn't offered`() {
        keepDownload(code = BuildConfig.VERSION_CODE)
        assertNull(ApkInstaller.downloaded(app))
    }

    @Test
    fun `Settings offers the kept download, and installs it without downloading again`() {
        app.getSharedPreferences(Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        keepDownload()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            fun settle() = repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.mainClock.advanceTimeBy(150) }
            fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            settle()
            // To Settings, then About.
            compose.onAllNodes(hasText("Settings") and hasClickAction()).let { nodes ->
                val all = nodes.fetchSemanticsNodes()
                nodes[all.indices.maxBy { all[it].boundsInRoot.top }].performClick()
            }
            repeat(10) { if (!shows("About")) settle() }
            compose.onAllNodes(hasText("About") and hasClickAction())[0].performClick()
            repeat(10) { if (!shows("(downloaded)")) settle() }
            val button = compose.onAllNodes(hasText("Install SpenDroid 9.9 (downloaded)") and hasClickAction())[0]
            button.performScrollTo()
            settle()
            button.performClick()
            repeat(100) { if (sessions.isEmpty()) { settle(); Thread.sleep(100) } }
            assertEquals("handed to Android's installer", 1, sessions.size)
        }
    }

    @Test
    fun `the not-installed notification's tap installs the kept download`() {
        app.getSharedPreferences(Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        keepDownload()
        val intent = Intent(app, MainActivity::class.java).putExtra(UpdateNotice.EXTRA_INSTALL, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            repeat(100) { if (sessions.isEmpty()) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(100) } }
            assertEquals("handed to Android's installer", 1, sessions.size)
        }
    }
}
