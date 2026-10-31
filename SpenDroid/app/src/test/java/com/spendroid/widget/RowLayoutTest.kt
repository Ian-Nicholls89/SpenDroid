package com.spendroid.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** "This period" shows every account chosen; short of room its rows tighten instead. */
class RowLayoutTest {

    @Test
    fun `three accounts on a 4x2 all show, compact`() {
        // A 4×2 is about 190dp tall on the user's phone: two full rows fit, three did not.
        assertEquals(RowDensity.COMPACT to 3, rowLayout(190f, 3))
    }

    @Test
    fun `three accounts on a 4x3 get full rows`() {
        assertEquals(RowDensity.FULL to 3, rowLayout(240f, 3))
    }

    @Test
    fun `a short widget falls back to single lines, still all three`() {
        assertEquals(RowDensity.LINE to 3, rowLayout(120f, 3))
    }

    @Test
    fun `only a widget too small for lines drops any`() {
        assertEquals(RowDensity.LINE to 1, rowLayout(80f, 3))
    }

    @Test
    fun `two accounts keep full rows where they fit`() {
        assertEquals(RowDensity.FULL to 2, rowLayout(170f, 2))
    }

    @Test
    fun `at a large text size the rows tighten rather than run off the bottom`() {
        // The user's phone, 0.8.0: three rows in a widget about 190dp tall at a large text size.
        assertEquals(RowDensity.COMPACT to 3, rowLayout(190f, 3, fontScale = 1f))
        assertEquals(RowDensity.LINE to 3, rowLayout(190f, 3, fontScale = 1.3f))
    }
}

