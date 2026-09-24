package com.spendroid.widget

import com.spendroid.domain.CreditCardEngine
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetGeometryTest {

    private val start = LocalDate.of(2026, 9, 10)
    private val end = LocalDate.of(2026, 10, 10)

    @Test
    fun `a date sits in proportion along the line, and never off it`() {
        assertEquals(0f, WidgetGeometry.fraction(start, end, start))
        assertEquals(0.5f, WidgetGeometry.fraction(start, end, LocalDate.of(2026, 9, 25)))
        assertEquals(1f, WidgetGeometry.fraction(start, end, LocalDate.of(2026, 11, 1)))
        assertEquals(0f, WidgetGeometry.fraction(start, end, LocalDate.of(2026, 9, 1)))
    }

    private fun bill(unbilled: Long, projected: Long?, cap: Long?, elapsed: Int = 15) = CreditCardEngine.CardBill(
        cardAccountId = "card",
        cardLabel = "Tesco",
        currency = "GBP",
        outstandingMinor = unbilled,
        billedMinor = 0,
        unbilledMinor = unbilled,
        dueDate = null,
        nominalPaymentDay = null,
        dueDateInferred = false,
        statementDay = 10,
        statementClose = start,
        cycleSource = CreditCardEngine.CycleSource.USER,
        totalSource = CreditCardEngine.TotalSource.BANK_BALANCE,
        cycleFitErrorMinor = null,
        cycleBillsChecked = null,
        nextStatementClose = end,
        statementDaysElapsed = elapsed,
        projectedMinor = projected,
        capMinor = cap,
    )

    @Test
    fun `under the limit, the bar is the limit`() {
        val rail = WidgetGeometry.cardRail(bill(unbilled = 2500, projected = 4000, cap = 5000))!!
        assertEquals(0.5f, rail.spent)
        assertEquals(0.8f, rail.projected)
        // Half way through the statement, an even pace is half the limit.
        assertEquals(0.5f, rail.evenPace!!, 0.0001f)
        assertNull(rail.limit)
    }

    @Test
    fun `heading past the limit, the bar stretches and marks where the limit was`() {
        val rail = WidgetGeometry.cardRail(bill(unbilled = 5000, projected = 10000, cap = 5000))!!
        assertEquals(0.5f, rail.spent)
        assertEquals(1f, rail.projected)
        assertEquals(0.5f, rail.limit!!)
        assertEquals(0.25f, rail.evenPace!!, 0.0001f)
    }

    @Test
    fun `no limit, no bar`() {
        assertNull(WidgetGeometry.cardRail(bill(unbilled = 2500, projected = 4000, cap = null)))
    }
}
