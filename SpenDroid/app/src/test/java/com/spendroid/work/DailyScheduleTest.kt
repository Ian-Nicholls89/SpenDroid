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
}
