package com.spendroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.3.2: Penny's motion - the keyframes, and the rules learnt from the mock-ups. */
class PennyMotionTest {

    @Test
    fun `keyframes hold before the first, ease between, and hold after the last`() {
        assertEquals(1f, kf(0f, 0.2f, 1f, 0.8f, 3f), 0f)
        assertEquals(2f, kf(0.5f, 0.2f, 1f, 0.8f, 3f), 0.001f)
        assertEquals(3f, kf(1f, 0.2f, 1f, 0.8f, 3f), 0f)
    }

    @Test
    fun `asleep has no glow - she's off`() {
        for (ms in 0L..6_000L step 100) {
            val p = poseAt(DroidMood.ASLEEP, DroidMoment.NONE, ms, ms, null, false)
            assertEquals(0f, p.fillGlow, 0f)
            assertEquals(0f, p.bulbGlow, 0f)
        }
    }

    @Test
    fun `the bulb is never faded, in any mood or moment`() {
        // Its glow may pulse; the bulb itself always draws at full - a faded bulb vanished.
        for (moment in DroidMoment.entries) for (mood in DroidMood.entries) for (ms in 0L..4_000L step 50) {
            val p = poseAt(mood, moment, ms, ms, ms, true)
            if (ms > 500) assertEquals("$mood $moment $ms", 1f, p.alpha, 0f)
        }
    }

    @Test
    fun `meet penny ends settled, back in her mood`() {
        val end = poseAt(DroidMood.AHEAD, DroidMoment.HELLO, 3_000, 3_000, null, false)
        val mood = poseAt(DroidMood.AHEAD, DroidMoment.NONE, 3_000, 3_000, null, false)
        assertEquals(mood, end)
        val landing = poseAt(DroidMood.AHEAD, DroidMoment.HELLO, 0, 0, null, false)
        assertTrue(landing.bodyDy < -50f)
    }

    @Test
    fun `a question stays asked, with the bulb amber`() {
        val p = poseAt(DroidMood.AHEAD, DroidMoment.QUESTION, 60_000 + 1_500, 1_500, null, false)
        assertEquals(1f, p.bulbAmber, 0.01f)
        assertTrue(p.question > 0.9f)
    }

    @Test
    fun `the visor fills on open, overshoots, and settles at the figure`() {
        assertEquals(0f, poseAt(DroidMood.AHEAD, DroidMoment.NONE, 100, 100, 100, true).fillScale, 0.01f)
        assertTrue(poseAt(DroidMood.AHEAD, DroidMoment.NONE, 1_200, 1_200, 1_200, true).fillScale > 1.05f)
        assertEquals(1f, poseAt(DroidMood.AHEAD, DroidMoment.NONE, 3_500, 3_500, 3_500, true).fillScale, 0f)
    }
}
