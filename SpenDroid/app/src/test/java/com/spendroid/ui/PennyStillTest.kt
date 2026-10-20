package com.spendroid.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 5.4: the widgets' Penny - a still of each mood, drawn by the same code as the app's. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PennyStillTest {

    @Test
    fun `every mood draws her, at the size asked`() {
        for (mood in DroidMood.entries) {
            val bitmap = pennyStill(DroidFace(mood, 0.4f, 0.5f), Color(0xFF3DDC84), 144)
            assertEquals(144, bitmap.width)
            assertEquals(144, bitmap.height)
            val pixels = IntArray(144 * 144).also { bitmap.getPixels(it, 0, 144, 0, 0, 144, 144) }
            // Her white outline is there, and the corners stay clear for the widget behind her.
            assertTrue("$mood has no outline", pixels.count { it == android.graphics.Color.WHITE } > 100)
            assertEquals(0, android.graphics.Color.alpha(pixels[0]))
        }
    }
}
