package com.spendroid.web

import androidx.test.core.app.ApplicationProvider
import com.spendroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.8.6: a smaller app. Lato is kept once - the computer link's page is served the app's own
 * copy - and cut to the alphabets the app shows (checked against every character in the source
 * when cut); the chimes are compressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSizeTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `every font the page asks for is served from the app's fonts`() {
        val css = context.assets.open("web/app.css").bufferedReader().readText()
        val asked = Regex("""url\(/(fonts/[^)]+)\)""").findAll(css).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("fonts/lato_regular.ttf", "fonts/lato_bold.ttf", "fonts/lato_black.ttf"), asked)
        assertEquals(asked, WebServer.FONTS.keys)
        WebServer.FONTS.values.forEach { id ->
            context.resources.openRawResource(id).use { stream ->
                // A TrueType file: version 1.0 at the front.
                val head = ByteArray(4).also { stream.read(it) }
                assertTrue(head.contentEquals(byteArrayOf(0, 1, 0, 0)))
            }
        }
    }

    @Test
    fun `the chimes are there to play`() {
        listOf(R.raw.chime_warm, R.raw.chime_shaped, R.raw.chime_sparkle).forEach { id ->
            assertTrue(context.resources.getResourceName(id), context.resources.openRawResource(id).use { it.available() } > 10_000)
        }
    }
}
