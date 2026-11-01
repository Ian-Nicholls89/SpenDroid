package com.spendroid.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "This period" shows every account chosen; short of room its rows tighten instead. The user's
 * 4x2 is about 193dp tall (0.8.3, from a screenshot), drawn at a large text size.
 */
class RowLayoutTest {

    private val standard = RowMetrics.standard(1f)
    private val large = RowMetrics.standard(1.3f)
    private val largest = RowMetrics.standard(1.45f)

    @Test
    fun `three accounts on the user's 4x2 get two-line rows, at their large text too`() {
        assertEquals(RowDensity.COMPACT to 3, rowLayout(193f, 3, standard))
        assertEquals(RowDensity.COMPACT to 3, rowLayout(193f, 3, large))
        assertEquals(RowDensity.COMPACT to 3, rowLayout(193f, 3, largest))
    }

    @Test
    fun `three accounts on a taller widget get full rows`() {
        assertEquals(RowDensity.FULL to 3, rowLayout(300f, 3, standard))
    }

    @Test
    fun `a short widget falls back to single lines, still all three`() {
        assertEquals(RowDensity.LINE to 3, rowLayout(150f, 3, standard))
    }

    @Test
    fun `only a widget too small for lines drops any`() {
        assertEquals(RowDensity.LINE to 1, rowLayout(90f, 3, standard))
    }

    @Test
    fun `what's chosen always fits`() {
        // Every height and count, at three text sizes: the rows chosen never need more than there is.
        listOf(standard, large, largest).forEach { m ->
            (60..400 step 5).forEach { h ->
                (1..4).forEach { n ->
                    val (density, shown) = rowLayout(h.toFloat(), n, m)
                    val row = when (density) {
                        RowDensity.FULL -> m.full
                        RowDensity.COMPACT -> m.compact
                        RowDensity.LINE -> m.line
                    }
                    val needed = 14f * 2 + m.header + shown * row
                    if (shown > 1 || density != RowDensity.LINE) {
                        assertTrue("h=$h n=$n $m: $density x$shown needs $needed", needed <= h)
                    }
                }
            }
        }
    }
}
