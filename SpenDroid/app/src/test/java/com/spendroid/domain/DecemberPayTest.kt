package com.spendroid.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Employers commonly pay early before Christmas, and just as commonly do not. The rule is
 * therefore opt-in: with nothing set, December behaves like any other month.
 */
class DecemberPayTest {

    private val salary = RecurringRule(
        key = "IN|GBP|1837|ukhsa",
        payee = "UKHSA",
        direction = Direction.IN,
        amountMinor = 183689,
        currency = "GBP",
        cadence = Cadence.MONTHLY,
        anchorDay = 25,
        lastOccurrence = LocalDate.of(2026, 11, 25),
        occurrences = 6,
        score = 0.8f,
    )

    private val christmas = WorkingDayCalendar(
        setOf(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 28)),
    )

    private fun next(after: LocalDate, decemberDay: Int?) = RecurringAnalyzer.nextOccurrence(
        rule = salary,
        after = after,
        calendar = christmas,
        shift = PaymentShift.PREVIOUS_WORKING_DAY,
        anchorDayOverride = 25,
        decemberAnchorDay = decemberDay,
    )

    @Test
    fun `without a December rule, the 25th is used and moved off the holiday`() {
        // 25 Dec 2026 is Christmas Day and a Friday, so wages land on Thursday the 24th.
        assertEquals(LocalDate.of(2026, 12, 24), next(LocalDate.of(2026, 12, 1), null))
    }

    @Test
    fun `a December rule moves pay day earlier`() {
        assertEquals(LocalDate.of(2026, 12, 18), next(LocalDate.of(2026, 12, 1), 18))
    }

    @Test
    fun `an early December day is still moved off a weekend`() {
        // 20 Dec 2026 is a Sunday, so it should come back to Friday the 18th.
        assertEquals(LocalDate.of(2026, 12, 18), next(LocalDate.of(2026, 12, 1), 20))
    }

    @Test
    fun `the December rule does not affect other months`() {
        // Asking after December rolls to January, which uses the standard 25th.
        val january = next(LocalDate.of(2026, 12, 31), 18)
        assertEquals(1, january.monthValue)
        // 25 Jan 2027 is a Monday, so no adjustment is needed.
        assertEquals(LocalDate.of(2027, 1, 25), january)
    }
}
