package com.spendroid.domain

import com.spendroid.data.BankHolidays
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingDaysTest {

    // Real England & Wales dates around Easter 2026 and Christmas 2026.
    private val holidays = setOf(
        LocalDate.of(2026, 4, 3),   // Good Friday
        LocalDate.of(2026, 4, 6),   // Easter Monday
        LocalDate.of(2026, 12, 25), // Christmas Day
        LocalDate.of(2026, 12, 28), // Boxing Day, substitute
    )
    private val calendar = WorkingDayCalendar(holidays)

    @Test
    fun `a weekend is not a working day`() {
        assertFalse(calendar.isWorkingDay(LocalDate.of(2026, 4, 25))) // Saturday
        assertFalse(calendar.isWorkingDay(LocalDate.of(2026, 4, 26))) // Sunday
        assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 4, 24)))  // Friday
    }

    @Test
    fun `wages on a Saturday are paid the Friday before`() {
        // 25 April 2026 is a Saturday.
        assertEquals(
            LocalDate.of(2026, 4, 24),
            calendar.adjust(LocalDate.of(2026, 4, 25), PaymentShift.PREVIOUS_WORKING_DAY),
        )
    }

    @Test
    fun `wages stepping back over Good Friday land on the Thursday`() {
        // 4 April 2026 is a Saturday; the Friday before is Good Friday, so step back again.
        assertEquals(
            LocalDate.of(2026, 4, 2),
            calendar.adjust(LocalDate.of(2026, 4, 4), PaymentShift.PREVIOUS_WORKING_DAY),
        )
    }

    @Test
    fun `a direct debit moves the other way, onto the next working day`() {
        assertEquals(
            LocalDate.of(2026, 4, 27),
            calendar.adjust(LocalDate.of(2026, 4, 25), PaymentShift.NEXT_WORKING_DAY),
        )
    }

    @Test
    fun `a run of Christmas closures is stepped over`() {
        // 26 and 27 Dec 2026 are the weekend, 28th is the substitute holiday.
        assertEquals(
            LocalDate.of(2026, 12, 29),
            calendar.adjust(LocalDate.of(2026, 12, 25), PaymentShift.NEXT_WORKING_DAY),
        )
    }

    @Test
    fun `an exact date is left alone`() {
        val saturday = LocalDate.of(2026, 4, 25)
        assertEquals(saturday, calendar.adjust(saturday, PaymentShift.NONE))
    }

    @Test
    fun `wages default to moving earlier and outgoings to later`() {
        assertEquals(PaymentShift.PREVIOUS_WORKING_DAY, PaymentShift.defaultFor(Direction.IN))
        assertEquals(PaymentShift.NEXT_WORKING_DAY, PaymentShift.defaultFor(Direction.OUT))
    }

    @Test
    fun `with no holidays loaded, weekends are still handled`() {
        val bare = WorkingDayCalendar()
        assertEquals(
            LocalDate.of(2026, 4, 24),
            bare.adjust(LocalDate.of(2026, 4, 25), PaymentShift.PREVIOUS_WORKING_DAY),
        )
    }

    @Test
    fun `the published calendar drops dates that have passed`() {
        val json = """
            {"england-and-wales":{"division":"england-and-wales","events":[
              {"title":"Old","date":"2019-01-01","notes":"","bunting":true},
              {"title":"Future","date":"2027-01-01","notes":"","bunting":true}
            ]}}
        """.trimIndent()
        val parsed = BankHolidays.parse(json, LocalDate.of(2026, 9, 23))
        assertEquals(1, parsed.size)
        assertEquals("2027-01-01", parsed.first().date)
    }

    @Test
    fun `a refetch is due only once the stored run is nearly spent`() {
        assertTrue(BankHolidays.needsRefresh(0))
        assertTrue(BankHolidays.needsRefresh(1))
        assertFalse(BankHolidays.needsRefresh(2))
        assertFalse(BankHolidays.needsRefresh(18))
    }
}
