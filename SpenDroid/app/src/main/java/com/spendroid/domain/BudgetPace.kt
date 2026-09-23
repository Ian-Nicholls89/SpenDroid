package com.spendroid.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Whether spending is keeping up with the cycle, rather than whether money is left.
 *
 * The distinction is the point: £200 left is comfortable on day 28 and alarming on day 3.
 * Comparing the fraction of the budget spent against the fraction of the cycle elapsed says
 * which of those it is.
 *
 * Shared between the home hero and the widget so the two cannot come to different verdicts
 * about the same day.
 */
object BudgetPace {

    enum class Pace {
        /** Spending is in step with the cycle, or ahead of it. */
        ON_TRACK,

        /** Running down faster than the days are. */
        TIGHT,

        /** Well past the pace, or nothing left at all. */
        OVER,
    }

    /** Spent so far as a fraction of the cycle's variable budget. */
    fun usedFraction(budget: BudgetSnapshot): Float? {
        if (budget.variableMonthlyBudget <= 0L) return null
        return (budget.spentThisCycle.toFloat() / budget.variableMonthlyBudget.toFloat())
            .coerceIn(0f, 1f)
    }

    /** How far through the pay cycle today is, as a fraction. */
    fun elapsedFraction(budget: BudgetSnapshot, today: LocalDate = LocalDate.now()): Float? {
        val end = budget.cycleEnd ?: return null
        val total = ChronoUnit.DAYS.between(budget.cycleStart, end).toFloat()
        if (total <= 0f) return null
        val gone = ChronoUnit.DAYS.between(budget.cycleStart, today).toFloat()
        return (gone / total).coerceIn(0f, 1f)
    }

    fun of(budget: BudgetSnapshot, today: LocalDate = LocalDate.now()): Pace {
        val used = usedFraction(budget)
        val elapsed = elapsedFraction(budget, today)
        if (used == null || elapsed == null) return Pace.ON_TRACK

        val overspend = used - elapsed
        return when {
            // Nothing left is worth saying loudly whatever the date.
            budget.availableToSpend <= 0L -> Pace.OVER
            overspend > 0.15f -> Pace.OVER
            overspend > 0.05f -> Pace.TIGHT
            else -> Pace.ON_TRACK
        }
    }

    /** How much of the budget remains, for a bar or an arc. */
    fun remainingFraction(budget: BudgetSnapshot): Float =
        usedFraction(budget)?.let { 1f - it } ?: 1f
}
