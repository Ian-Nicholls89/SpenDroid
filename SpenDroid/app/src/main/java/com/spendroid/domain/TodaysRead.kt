package com.spendroid.domain

/**
 * The droid's one line on Home: how it's going, said plainly, and what keeps it going. Worked
 * out from the same pace as the bar and the droid's face, so the three never disagree.
 */
object TodaysRead {

    fun of(budget: BudgetSnapshot, money: (Long) -> String): String {
        val days = budget.daysUntilNextIncome?.takeIf { it > 0 }
        val daily = days?.let { money(maxOf(0L, budget.availableToSpend) / it) }
        if (budget.asOf == budget.cycleStart) {
            return "Payday! " + (days?.let { "${money(budget.availableToSpend)} for the next $it days." } ?: "A fresh start.")
        }
        if (budget.availableToSpend <= 0L) {
            return "Nothing left to spend before payday" + (budget.nextIncomeDate?.let { " on ${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::titlecase)}" } ?: "") + "."
        }
        val ahead = aheadMinor(budget)
        return when (BudgetPace.of(budget)) {
            BudgetPace.Pace.OVER -> "Over for the days so far." + (daily?.let { " $it a day gets you to payday." } ?: "")
            BudgetPace.Pace.TIGHT -> "Just behind the days." + (daily?.let { " $it a day keeps you on track." } ?: "")
            BudgetPace.Pace.ON_TRACK -> when {
                ahead != null && ahead >= AHEAD_WORTH_SAYING -> "${money(ahead)} ahead of the days. A normal day keeps you there."
                else -> "Right on pace." + (daily?.let { " $it a day gets you to payday." } ?: "")
            }
        }
    }

    /** How far spending is behind the days, in money: what the days allowed so far, less what's used. */
    fun aheadMinor(budget: BudgetSnapshot): Long? {
        val used = BudgetPace.usedFraction(budget) ?: return null
        val elapsed = BudgetPace.elapsedFraction(budget) ?: return null
        val against = budget.spendableThisCycle.takeIf { it > 0L } ?: budget.variableMonthlyBudget.takeIf { it > 0L } ?: return null
        return ((elapsed - used) * against).toLong()
    }

    /** Under a fiver ahead is "on pace", not "ahead". */
    private const val AHEAD_WORTH_SAYING = 500L
}
