package com.spendroid.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Penny's line on a card's or another account's page of Home: its own pace, said plainly. */
class AccountReadTest {

    private fun money(minor: Long) = "£%d.%02d".format(minor / 100, minor % 100)

    private fun row(
        kind: PeriodRows.Kind,
        spent: Long,
        against: Long?,
        pace: BudgetPace.Pace = BudgetPace.Pace.ON_TRACK,
        projected: Long? = null,
        whose: PeriodRows.Against? = against?.let { PeriodRows.Against.USUAL },
    ) = PeriodRows.Row("a", "Card", kind, spent, against, whose, null, null, 7, 28, 0L, projected, pace, "GBP")

    private fun read(r: PeriodRows.Row) = TodaysRead.of(r, ::money)

    @Test
    fun `a card says where its bill is heading against the usual or the limit`() {
        assertEquals("Heading for about £150.00, under your usual £200.00 bill.", read(row(PeriodRows.Kind.STATEMENT, 6_000, 20_000, projected = 15_000)))
        assertEquals("Heading for about £320.00 - more than your usual £200.00 bill.", read(row(PeriodRows.Kind.STATEMENT, 10_000, 20_000, BudgetPace.Pace.OVER, 32_000)))
        assertEquals("Already past your £150.00 limit, at £160.00.", read(row(PeriodRows.Kind.STATEMENT, 16_000, 15_000, BudgetPace.Pace.OVER, whose = PeriodRows.Against.LIMIT)))
        assertEquals("£60.00 on the card since the last statement.", read(row(PeriodRows.Kind.STATEMENT, 6_000, null)))
    }

    @Test
    fun `another account is measured against its own usual cycle`() {
        assertEquals("Steady - £20.00 out by day 7, of a usual £108.00 for the cycle.", read(row(PeriodRows.Kind.CYCLE, 2_000, 10_800)))
        assertEquals("A little quicker than usual - £35.00 out by day 7, of a usual £108.00.", read(row(PeriodRows.Kind.CYCLE, 3_500, 10_800, BudgetPace.Pace.TIGHT)))
        assertEquals("More out than a usual whole cycle already: £120.00, against about £108.00.", read(row(PeriodRows.Kind.CYCLE, 12_000, 10_800, BudgetPace.Pace.OVER)))
        assertEquals(
            "£20.00 out so far this pay cycle. A cycle or two more and Penny will know what's usual here.",
            read(row(PeriodRows.Kind.CYCLE, 2_000, null)),
        )
    }
}
