package com.spendroid.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.8.2: the Available to spend widget's bar in two, as Home's - the account's part, then the cards'. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class SplitVisorTest {

    @Test
    fun `the account's part, then the cards', then the track`() {
        val fill = 0xFF43B05C.toInt()
        val track = 0xFF2A2C33.toInt()
        val bar = visorBitmap(200f, 26f, used = 0.2f, tick = null, fill = fill, track = track, cards = 0.3f)
        val y = bar.height / 2
        assertEquals("account", fill, bar.getPixel((bar.width * 0.10f).toInt(), y))
        assertEquals("cards", CARDS_COLOUR, bar.getPixel((bar.width * 0.40f).toInt(), y))
        assertEquals("the rest", track, bar.getPixel((bar.width * 0.75f).toInt(), y))
    }

    @Test
    fun `without cards it's one part, as before`() {
        val fill = 0xFF43B05C.toInt()
        val track = 0xFF2A2C33.toInt()
        val bar = visorBitmap(200f, 26f, used = 0.2f, tick = null, fill = fill, track = track)
        assertEquals(track, bar.getPixel((bar.width * 0.40f).toInt(), bar.height / 2))
    }
}
