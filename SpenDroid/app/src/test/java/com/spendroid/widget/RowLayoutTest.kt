package com.spendroid.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "This period" shows every account chosen; short of room its rows tighten instead. Sizes as the
 * user's phone draws them (0.8.3): a 4x2 there is about 193dp tall.
 */
class RowLayoutTest {

    @Test
    fun `three accounts on the user's 4x2 get two-line rows`() {
        assertEquals(RowDensity.COMPACT to 3, rowLayout(193f, 3))
    }

    @Test
    fun `three accounts on a taller widget get full rows`() {
        assertEquals(RowDensity.FULL to 3, rowLayout(280f, 3))
    }

    @Test
    fun `a short widget falls back to single lines, still all three`() {
        assertEquals(RowDensity.LINE to 3, rowLayout(150f, 3))
    }

    @Test
    fun `only a widget too small for lines drops any`() {
        assertEquals(RowDensity.LINE to 1, rowLayout(80f, 3))
    }

    @Test
    fun `two accounts keep full rows where they fit`() {
        assertEquals(RowDensity.FULL to 2, rowLayout(200f, 2))
    }

    @Test
    fun `at a large text size the rows tighten rather than run off the bottom`() {
        assertEquals(RowDensity.COMPACT to 3, rowLayout(190f, 3, fontScale = 1f))
        assertEquals(RowDensity.LINE to 3, rowLayout(190f, 3, fontScale = 1.3f))
    }

    @Test
    fun `what's chosen always fits`() {
        // Every height and count, at three text sizes: the rows chosen never need more than there is.
        listOf(1f, 1.15f, 1.3f).forEach { s ->
            (60..400 step 5).forEach { h ->
                (1..4).forEach { n ->
                    val (density, shown) = rowLayout(h.toFloat(), n, s)
                    val row = when (density) {
                        RowDensity.FULL -> 26f + 44f * s
                        RowDensity.COMPACT -> 14f + 22f * s
                        RowDensity.LINE -> 10f + 20f * s
                    }
                    val needed = 20f + 30f * s + shown * row
                    if (shown > 1 || density != RowDensity.LINE) {
                        assert(needed <= h) { "h=$h n=$n s=$s: $density x$shown needs $needed" }
                    }
                }
            }
        }
    }
}
