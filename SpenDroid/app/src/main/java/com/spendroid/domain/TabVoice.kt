package com.spendroid.domain

import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What Penny says on the Spending, Accounts and Regular tabs (0.7.0): only what helps on that tab,
 * only when it's true and worth saying - nothing at all otherwise, and her face goes back to the
 * banner. Each line knows what it's about ([Target]), so the bubble's link goes there.
 *
 * Home has [PennyVoice]; nothing said here is said there.
 */
object TabVoice {

    data class Line(val text: String, val rank: Int, val target: Target? = null)

    /** What a line is about: where its link goes. */
    sealed interface Target {
        data class Bill(val ruleKey: String) : Target
        data class Spend(val category: Category) : Target
        data class Shop(val name: String) : Target
        data object Pending : Target
        data object Transfers : Target
        data class Relink(val requisitionId: String) : Target
        data class Import(val accountId: String) : Target
    }

    // ---- Spending ------------------------------------------------------------------------------

    /**
     * This cycle's everyday spending, from the accounts and the cards: not bills, not card bills
     * paid, not money moved between the user's own accounts. [categoryOf] files each as Spending does.
     */
    fun spending(
        budget: BudgetSnapshot,
        transactions: List<TransactionEntity>,
        money: (Long) -> String,
        categoryOf: (TransactionEntity) -> Category,
    ): List<Line> {
        val today = budget.asOf
        val out = mutableListOf<Line>()
        val billKeys = budget.fixedRules.map { it.key }.toSet()
        fun dateOf(tx: TransactionEntity) = RecurringAnalyzer.parseBookingDate(tx.bookingDate)
        val cycle = transactions.filter { tx ->
            val d = dateOf(tx) ?: return@filter false
            !d.isBefore(budget.cycleStart) && !d.isAfter(today)
        }
        val everyday = cycle.filter { tx ->
            tx.amountMinor < 0 && !tx.isInternalTransfer && !tx.isCardPayment &&
                "${tx.accountId}|${tx.transactionId}" !in budget.cardPaymentKeys &&
                RecurringAnalyzer.groupKey(tx) !in billKeys
        }

        // Pending: counted already, and settling.
        val pending = transactions.filter { it.isPending && it.amountMinor < 0 && !it.isInternalTransfer }
        if (pending.isNotEmpty()) {
            val total = pending.sumOf { -it.amountMinor }
            val n = pending.size
            out += Line(
                "${money(total)} still pending${if (n > 1) " across $n payments" else ""} - already counted.",
                55, Target.Pending,
            )
        }

        // The shop visited most this cycle.
        // Shops by name alone, the branch left off: "Tesco Salisbury" and "Tesco Bath" are Tesco.
        val places = ShopName.placesIn(transactions.map { it.payee })
        val topShop = everyday.groupBy { name(it.payee, places) }
            .filterKeys { it.isNotBlank() }
            .maxByOrNull { it.value.size }
            ?.takeIf { it.value.size >= 4 }
            ?.also { (shop, rows) ->
                out += Line("$shop ${rows.size} times this cycle - ${money(rows.sumOf { -it.amountMinor })}, your most-visited.", 60, Target.Shop(shop))
            }

        // Filed as Other: a nudge to sort them, which teaches her.
        val other = everyday.filter { categoryOf(it) == Category.OTHER }
        if (other.size >= 3) {
            out += Line("${other.size} payments in Other this cycle. File one and I'll learn it.", 65, Target.Spend(Category.OTHER))
        }

        // Where most of it went.
        val byCategory = everyday.groupBy(categoryOf).mapValues { (_, rows) -> rows.sumOf { -it.amountMinor } }
        val total = byCategory.values.sum()
        byCategory.filterKeys { it != Category.OTHER }.maxByOrNull { it.value }
            ?.takeIf { total > 0 && it.value >= 2_000L && it.value * 100 / total >= 30 }
            // Not when it's all the one shop just named: the same figure twice.
            ?.takeIf { c -> topShop == null || topShop.value.sumOf { -it.amountMinor } * 10 < c.value * 9 }
            ?.let { (category, minor) ->
                out += Line("Most of this cycle's spending is ${category.label.lowercase()}: ${money(minor)}.", 50, Target.Spend(category))
            }

        // Money moved between the user's own accounts.
        val moved = cycle.filter {
            it.isInternalTransfer && it.amountMinor < 0 && !it.isCardPayment &&
                "${it.accountId}|${it.transactionId}" !in budget.cardPaymentKeys
        }.sumOf { -it.amountMinor }
        if (moved >= 1_000L) {
            out += Line("${money(moved)} moved between your accounts - not counted as spending.", 45, Target.Transfers)
        }
        return out.sortedByDescending { it.rank }
    }

    // ---- Regular -------------------------------------------------------------------------------

    fun regular(budget: BudgetSnapshot, outlook: Outlook, money: (Long) -> String): List<Line> {
        val today = budget.asOf
        val out = mutableListOf<Line>()

        // A bill that's changed price lately.
        outlook.priceChanges.values
            .filter { !it.on.isBefore(today.minusDays(45)) && it.afterMinor != it.beforeMinor }
            .maxByOrNull { it.on }
            ?.let { c ->
                val diff = kotlin.math.abs(c.afterMinor) - kotlin.math.abs(c.beforeMinor)
                val who = name(c.payee)
                out += Line(
                    if (diff > 0) "$who went up ${money(diff)} - planned for at the new amount."
                    else "$who came down ${money(-diff)} - planned for at the new amount.",
                    80, Target.Bill(c.ruleKey),
                )
            }

        // A contract ending soon.
        outlook.contracts.values
            .mapNotNull { e -> (e.status as? Contracts.Status.EndingSoon)?.let { e to it.daysLeft } }
            .minByOrNull { it.second }
            ?.let { (e, days) ->
                out += Line("${name(e.payee)}'s contract ends ${inDays(days)}. Worth a look before it renews.", 75, Target.Bill(e.key))
            }

        // A bill due on payday itself: next month's money, so not in this cycle's - said why, in
        // the cycle's last ten days, when it's among what's coming.
        budget.nextIncomeDate?.takeIf { !it.isAfter(today.plusDays(10)) }?.let { payday ->
            budget.fixedRules
                .filter { it.direction == Direction.OUT && it.cadence == Cadence.MONTHLY && it.anchorDay == payday.dayOfMonth }
                .maxByOrNull { kotlin.math.abs(it.amountMinor) }
                ?.let { rule ->
                    out += Line("${name(rule.payee)} is due on payday, so it's next month's money.", 70, Target.Bill(rule.key))
                }
        }

        // The next big one this week.
        val total = budget.upcomingFixed.sumOf { it.amountMinor }
        budget.upcomingFixed
            .filter { !it.dueDate.isBefore(today) && !it.dueDate.isAfter(today.plusDays(6)) }
            .maxByOrNull { it.amountMinor }
            ?.takeIf { total > 0 && it.amountMinor * 100 / total >= 15 && it.amountMinor >= 5_000L }
            ?.let { p ->
                out += Line("Next big one: ${name(p.rule.payee)}, ${money(p.amountMinor)} ${whenDue(p.dueDate, today)}.", 60, Target.Bill(p.rule.key))
            }

        // Bills against income, as Each month has them.
        if (budget.averageMonthlyIncome > 0 && budget.fixedMonthlyOutgoings > 0) {
            val share = budget.fixedMonthlyOutgoings * 100 / budget.averageMonthlyIncome
            val left = budget.averageMonthlyIncome - budget.fixedMonthlyOutgoings
            if (left > 0) out += Line("Bills take $share% of your income, leaving ${money(left)} a month.", 40)
        }
        return out.sortedByDescending { it.rank }
    }

    // ---- Accounts ------------------------------------------------------------------------------

    fun accounts(
        budget: BudgetSnapshot?,
        accounts: List<AccountEntity>,
        connections: List<Connection>,
        manualCoverage: Map<String, LocalDate?>,
        today: LocalDate,
        money: (Long) -> String,
    ): List<Line> {
        val out = mutableListOf<Line>()

        // A bank link about to lapse: the one thing here that stops the figures if missed.
        connections.mapNotNull { c -> c.daysUntilExpiry(today)?.let { c to it } }
            .filter { it.second in 0..14 }
            .minByOrNull { it.second }
            ?.let { (c, days) ->
                out += Line(
                    "${c.institutionName}'s link needs renewing ${if (days == 0) "today" else inDays(days.toLong())}.",
                    90, Target.Relink(c.requisitionId),
                )
            }

        // A card's bill due soon, or its statement about to close.
        budget?.cardBills?.forEach { bill ->
            val due = bill.dueDate
            if (due != null && !bill.statementPaid && bill.dueMinor > 0 && !due.isBefore(today) && !due.isAfter(today.plusDays(10))) {
                out += Line("${bill.cardLabel.trim()}'s ${money(bill.dueMinor)} bill goes ${whenDue(due, today)} - set aside.", 70)
            } else {
                val close = bill.nextStatementClose
                val heading = bill.projectedMinor
                if (close != null && heading != null && heading > 0 && !close.isBefore(today) && !close.isAfter(today.plusDays(5))) {
                    out += Line("${bill.cardLabel.trim()}'s statement closes ${whenDue(close, today)}, at about ${money(heading)}.", 65)
                }
            }
        }

        // An account with no bank link, its figures getting old.
        accounts.filter { it.manual && it.accountType != AccountType.CREDIT_CARD }.forEach { a ->
            val last = manualCoverage[a.id] ?: return@forEach
            if (ChronoUnit.DAYS.between(last, today) >= 35) {
                out += Line("No statement for ${a.label.trim()} since ${last.format(DAY_MONTH)}. Import one?", 60, Target.Import(a.id))
            }
        }
        return out.sortedByDescending { it.rank }
    }

    // ---- words ---------------------------------------------------------------------------------

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    private fun name(payee: String, places: Set<String> = emptySet()): String = ShopName.clean(payee, places) ?: payee.trim()

    private fun whenDue(date: LocalDate, today: LocalDate): String = when (val d = ChronoUnit.DAYS.between(today, date)) {
        0L -> "today"
        1L -> "tomorrow"
        in 2L..6L -> "on " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        else -> "on " + date.format(WEEKDAY_DAY_MONTH)
    }

    private fun inDays(days: Long): String = when {
        days <= 0L -> "today"
        days == 1L -> "tomorrow"
        days < 14L -> "in $days days"
        else -> "in ${days / 7} weeks"
    }
}
