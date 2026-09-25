package com.spendroid.work

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The roundup set for 21:00 arrived at five in the afternoon: repeating jobs keep "once a
 * day", not a time. Each run is now booked for the next occurrence of the chosen time.
 */
class DailyScheduleTest {

    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int = 0) =
        LocalDateTime.of(y, m, d, h, min, s).atZone(london)

    @Test
    fun `earlier in the day, it is later today`() {
        assertEquals(
            Duration.ofHours(4),
            DailyRoundupScheduler.delayUntilNext(at(2026, 9, 24, 17, 0), LocalTime.of(21, 0)),
        )
    }

    /** A run finishing just after its own time books tomorrow, not a second one now. */
    @Test
    fun `just after the time, it is tomorrow`() {
        assertEquals(
            Duration.ofHours(24).minusSeconds(5),
            DailyRoundupScheduler.delayUntilNext(at(2026, 9, 24, 21, 0, 5), LocalTime.of(21, 0)),
        )
    }

    /** The clocks go back on 25 October 2026: that day is 25 hours long, and 21:00 is still 21:00. */
    @Test
    fun `across the clock change it is still the same time on the clock`() {
        val delay = DailyRoundupScheduler.delayUntilNext(at(2026, 10, 24, 21, 0, 1), LocalTime.of(21, 0))
        assertEquals(Duration.ofHours(25).minusSeconds(1), delay)
    }

    private fun times(notification: String) =
        DailyRoundupScheduler.syncTimes(LocalTime.parse(notification)).map { it.toString() }

    /**
     * Morning, afternoon and evening, never overnight, with the one nearest the roundup
     * moved to an hour before it so the roundup always reports fresh figures.
     */
    @Test
    fun `syncs sit in the day, and one lands an hour before the roundup`() {
        assertEquals(listOf("08:00", "14:00", "20:00"), times("21:00"))
        assertEquals(listOf("06:00", "14:00", "20:00"), times("07:00"))
        assertEquals(listOf("11:00", "14:00", "20:00"), times("12:00"))
        assertEquals(listOf("08:00", "14:00", "21:00"), times("22:00"))
        assertEquals(listOf("08:00", "14:00", "18:00"), times("19:00"))
    }

    /** An hour before a small-hours roundup would be the middle of the night, so it is left alone. */
    @Test
    fun `nothing is moved into the night`() {
        assertEquals(listOf("08:00", "14:00", "20:00"), times("02:00"))
    }

    /** At least three hours apart, whatever the roundup time, so no sync makes the next one skip. */
    @Test
    fun `no two syncs are ever close together`() {
        (0 until 24 * 4).map { LocalTime.of(it / 4, (it % 4) * 15) }.forEach { roundup ->
            val slots = DailyRoundupScheduler.syncTimes(roundup)
            assertEquals(3, slots.size)
            slots.zipWithNext { a, b ->
                val gap = Duration.between(a, b).toMinutes()
                assert(gap >= 180) { "$roundup gives $slots" }
            }
            slots.forEach { assert(it.hour in 6..22) { "$roundup puts a sync at $it" } }
        }
    }
}
