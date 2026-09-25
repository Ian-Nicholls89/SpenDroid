package com.spendroid.domain

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The day starts with its first sync, not at midnight - within reason. */
class BudgetDayTest {

    private val eight = LocalTime.of(8, 0)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, d, h, m)

    @Test
    fun `before the first sync it is still yesterday`() {
        val day = BudgetDay.effective(now = at(25, 7, 30), lastSync = at(24, 20, 5), firstSync = eight)
        assertEquals(at(24, 23, 59).toLocalDate(), day.toLocalDate())
    }

    @Test
    fun `once today has synced it is today`() {
        val now = at(25, 8, 10)
        assertEquals(now, BudgetDay.effective(now, lastSync = at(25, 8, 2), firstSync = eight))
    }

    /** Offline all morning, the app does not stay on yesterday past midday. */
    @Test
    fun `four hours after the first sync was due, the day starts anyway`() {
        val now = at(25, 12, 0)
        assertEquals(now, BudgetDay.effective(now, lastSync = at(24, 20, 5), firstSync = eight))
        assertEquals(24, BudgetDay.effective(at(25, 11, 59), at(24, 20, 5), eight).dayOfMonth)
    }

    @Test
    fun `never synced, there is nothing to wait for`() {
        val now = at(25, 7, 0)
        assertEquals(now, BudgetDay.effective(now, lastSync = null, firstSync = eight))
    }
}
