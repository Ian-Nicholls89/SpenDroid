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

    /**
     * Penny's line for an account the budget doesn't spend from: a card against its usual bill
     * or the user's limit, any other account against what it usually spends in a cycle. From
     * the same row as the page's bar, so the line, the bar and her face agree.
     */
    fun of(row: PeriodRows.Row, money: (Long) -> String): String {
        val spent = money(row.spentMinor)
        val against = row.againstMinor
        if (row.kind == PeriodRows.Kind.STATEMENT) {
            if (against == null) return "$spent on the card since the last statement."
            val whose = if (row.against == PeriodRows.Against.LIMIT) "your ${money(against)} limit" else "your usual ${money(against)} bill"
            val heading = row.projectedMinor?.takeIf { it > row.spentMinor }
            return when {
                row.spentMinor >= against -> "Already past $whose, at $spent."
                row.pace == BudgetPace.Pace.OVER -> heading?.let { "Heading for about ${money(it)} - more than $whose." } ?: "Quicker than usual - $spent already, against $whose."
                row.pace == BudgetPace.Pace.TIGHT -> heading?.let { "A little quick - heading for about ${money(it)}, against $whose." } ?: "A little quicker than usual, against $whose."
                heading != null && heading < against -> "Heading for about ${money(heading)}, under $whose."
                else -> "$spent so far, well inside $whose."
            }
        }
        if (against == null) return "$spent out so far this pay cycle. A cycle or two more and I'll know what's usual here."
        val usual = money(against)
        val byDay = row.day?.let { " by day $it" }.orEmpty()
        return when {
            row.spentMinor >= against -> "More out than a usual whole cycle already: $spent, against about $usual."
            row.pace == BudgetPace.Pace.OVER -> "Faster than usual - $spent out$byDay, of a usual $usual for the cycle."
            row.pace == BudgetPace.Pace.TIGHT -> "A little quicker than usual - $spent out$byDay, of a usual $usual."
            else -> "Steady - $spent out$byDay, of a usual $usual for the cycle."
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
