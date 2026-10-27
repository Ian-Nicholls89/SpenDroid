package com.spendroid.ui

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.0: Penny asleep on the widgets and the watch's tile, 11pm to 6am. */
class PennySleepTest {

    @Test
    fun `asleep from eleven to six, awake otherwise`() {
        assertFalse(PennySleep.asleep(LocalTime.of(22, 59)))
        assertTrue(PennySleep.asleep(LocalTime.of(23, 0)))
        assertTrue(PennySleep.asleep(LocalTime.of(3, 0)))
        assertTrue(PennySleep.asleep(LocalTime.of(5, 59)))
        assertFalse(PennySleep.asleep(LocalTime.of(6, 0)))
    }

    @Test
    fun `her face at night, unless she's syncing`() {
        assertEquals(DroidMood.ASLEEP, PennySleep.at(DroidFace(DroidMood.PAYDAY), LocalTime.of(2, 0)).mood)
        assertEquals(DroidMood.PAYDAY, PennySleep.at(DroidFace(DroidMood.PAYDAY), LocalTime.of(6, 0)).mood)
        assertEquals(DroidMood.SYNCING, PennySleep.at(DroidFace(DroidMood.SYNCING), LocalTime.of(2, 0)).mood)
    }

    @Test
    fun `the next redraw is when she falls asleep or wakes`() {
        assertEquals(LocalDateTime.of(2026, 10, 27, 23, 0), PennySleep.nextChange(LocalDateTime.of(2026, 10, 27, 14, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 28, 6, 0), PennySleep.nextChange(LocalDateTime.of(2026, 10, 27, 23, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 27, 6, 0), PennySleep.nextChange(LocalDateTime.of(2026, 10, 27, 1, 0)))
    }
}
