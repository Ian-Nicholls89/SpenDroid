package com.spendroid.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.8.3: Available to spend's wide layout gets "Spent today · Next: …" only where it fits. */
class StandardExtraTest {

    @Test
    fun `the user's 4x2 has room for it`() {
        // About 193dp tall on the user's phone, with room to spare under the figures.
        assertTrue(193f >= standardWithExtra(1f))
    }

    @Test
    fun `a shorter widget, or a larger text size, goes without`() {
        assertFalse(140f >= standardWithExtra(1f))
        assertFalse(165f >= standardWithExtra(1.3f))
    }
}
