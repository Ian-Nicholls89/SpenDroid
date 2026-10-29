package com.spendroid.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Easter question, pinned down.
 *
 * Hypothetical: Good Friday on 24 April, Easter Monday on the 27th. Wages nominally due on
 * Saturday the 25th, outgoings nominally due on Good Friday itself.
 */
class EasterScenarioTest {

    private val calendar = WorkingDayCalendar(
        setOf(
            LocalDate.of(2026, 4, 24), // Good Friday
            LocalDate.of(2026, 4, 27), // Easter Monday
        ),
    )

    @Test
    fun `wages due on the Saturday are paid the Thursday before`() {
        // Back past Saturday, then past Good Friday, landing on the Thursday.
        assertEquals(
            LocalDate.of(2026, 4, 23),
            calendar.adjust(LocalDate.of(2026, 4, 25), PaymentShift.PREVIOUS_WORKING_DAY),
        )
    }

    @Test
    fun `an outgoing due on Good Friday is taken the Tuesday after`() {
        // Forward past the weekend and Easter Monday. Saturday is never an option: the banks
        // are not open, so a direct debit cannot be collected then.
        assertEquals(
            LocalDate.of(2026, 4, 28),
            calendar.adjust(LocalDate.of(2026, 4, 24), PaymentShift.NEXT_WORKING_DAY),
        )
    }

    @Test
    fun `the whole closure is four days long`() {
        listOf(24, 25, 26, 27).forEach { day ->
            assertEquals(
                "24-27 April should all be closed",
                false,
                calendar.isWorkingDay(LocalDate.of(2026, 4, day)),
            )
        }
        assertEquals(true, calendar.isWorkingDay(LocalDate.of(2026, 4, 23)))
        assertEquals(true, calendar.isWorkingDay(LocalDate.of(2026, 4, 28)))
    }
}
