package com.spendroid.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hero and the widget both colour themselves from this, so a disagreement here would show
 * up as the app and the home screen contradicting each other about the same day.
 */
class BudgetPaceTest {

    private fun snapshot(
        budgetMinor: Long = 100000,
        spentMinor: Long = 0,
        availableMinor: Long = 50000,
        cycleStart: LocalDate = LocalDate.of(2026, 9, 1),
        cycleEnd: LocalDate? = LocalDate.of(2026, 10, 1),
    ) = BudgetSnapshot(
        averageMonthlyIncome = 200000,
        fixedMonthlyOutgoings = 100000,
        variableMonthlyBudget = budgetMinor,
        nextIncomeDate = cycleEnd,
        cycleStart = cycleStart,
        cycleEnd = cycleEnd,
        spentThisCycle = spentMinor,
        spentToday = 0,
        upcomingFixed = emptyList(),
        availableToSpend = availableMinor,
        incomeRules = emptyList(),
        fixedRules = emptyList(),
        primaryIncomeRule = null,
        daysUntilNextIncome = 10,
    )

    /** Half the budget gone at the halfway point is exactly on pace. */
    @Test
    fun `spending in step with the cycle is on track`() {
        val pace = BudgetPace.of(
            snapshot(spentMinor = 50000),
            today = LocalDate.of(2026, 9, 16),
        )
        assertEquals(BudgetPace.Pace.ON_TRACK, pace)
    }

    @Test
    fun `running ahead of the days is tight, then over`() {
        val day10 = LocalDate.of(2026, 9, 11)
        // A third of the month gone, 43% of the budget with it.
        assertEquals(BudgetPace.Pace.TIGHT, BudgetPace.of(snapshot(spentMinor = 43000), day10))
        // Over half of it, with two thirds of the month still to run.
        assertEquals(BudgetPace.Pace.OVER, BudgetPace.of(snapshot(spentMinor = 55000), day10))
    }

    /** Whatever the date, nothing left is nothing left. */
    @Test
    fun `an exhausted budget is over regardless of how far through the cycle it is`() {
        val pace = BudgetPace.of(
            snapshot(spentMinor = 99000, availableMinor = 0),
            today = LocalDate.of(2026, 9, 29),
        )
        assertEquals(BudgetPace.Pace.OVER, pace)
    }

    /** Underspending should never be flagged, however late in the cycle. */
    @Test
    fun `spending well under the pace stays on track late in the cycle`() {
        val pace = BudgetPace.of(
            snapshot(spentMinor = 20000),
            today = LocalDate.of(2026, 9, 28),
        )
        assertEquals(BudgetPace.Pace.ON_TRACK, pace)
    }

    @Test
    fun `no budget and no cycle end cannot be judged, so nothing is claimed`() {
        assertEquals(BudgetPace.Pace.ON_TRACK, BudgetPace.of(snapshot(budgetMinor = 0)))
        assertEquals(BudgetPace.Pace.ON_TRACK, BudgetPace.of(snapshot(cycleEnd = null)))
    }

    @Test
    fun `the remaining fraction is what the bar and the arc draw`() {
        assertEquals(0.25f, BudgetPace.remainingFraction(snapshot(spentMinor = 75000)), 0.001f)
        // Overspent clamps at empty rather than going negative and inverting the bar.
        assertEquals(0f, BudgetPace.remainingFraction(snapshot(spentMinor = 130000)), 0.001f)
    }
}
