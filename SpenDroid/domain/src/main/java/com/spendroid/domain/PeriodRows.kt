package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One row per account for the "This period" widget: what has gone out this period, what it is
 * measured against, and how far through the period today is.
 *
 * The period is the account's own: the pay cycle for a current or joint account, the statement
 * for a credit card. The account the budget is paid into is measured against the budget; a card
 * against its usual bill or the user's limit; any other account against what it usually spends in
 * a cycle, since the budget does not cover it.
 */
object PeriodRows {

    enum class Kind { BUDGET, CYCLE, STATEMENT }

    data class Row(
        val accountId: String,
        val label: String,
        val kind: Kind,
        val spentMinor: Long,
        /** What the spending is measured against, or null when there is nothing to measure by yet. */
        val againstMinor: Long?,
        /** Whose figure [againstMinor] is: the budget, the usual, or the user's limit. */
        val against: Against?,
        /** Share of [againstMinor] gone, 0 to 1; null without one. */
        val used: Float?,
        /** Share of the period gone, 0 to 1; null when the period is not known. */
        val gone: Float?,
        val day: Int?,
        val days: Int?,
        val pendingMinor: Long,
        val projectedMinor: Long?,
        val pace: BudgetPace.Pace,
        val currency: String,
        /** A card's regular payments still to come before its statement closes. */
        val toComeMinor: Long = 0L,
    )

    enum class Against { BUDGET, USUAL, LIMIT }

    /** Rows for [accountIds], in that order; accounts that no longer exist are left out. */
    fun rows(
        accountIds: List<String>,
        snapshot: BudgetSnapshot,
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
    ): List<Row> = accountIds.mapNotNull { id ->
        val account = accounts.firstOrNull { it.id == id } ?: return@mapNotNull null
        when {
            account.accountType == AccountType.CREDIT_CARD -> cardRow(account, snapshot)
            isBudgetAccount(account, snapshot, accounts) -> budgetRow(account, snapshot)
            else -> cycleRow(account, snapshot, transactions)
        }
    }

    /** The account the budget is paid from: where the income lands, else the only personal one. */
    private fun isBudgetAccount(account: AccountEntity, snapshot: BudgetSnapshot, accounts: List<AccountEntity>): Boolean =
        snapshot.potAccountId?.let { it == account.id }
            ?: (account.accountType == AccountType.PERSONAL && accounts.count { it.accountType == AccountType.PERSONAL } == 1)

    private fun budgetRow(account: AccountEntity, s: BudgetSnapshot): Row {
        val (day, days) = dayOf(s.cycleStart, s.cycleEnd, s.asOf)
        return Row(
            accountId = account.id,
            label = account.label,
            kind = Kind.BUDGET,
            // Gone from the bar, not only spent, so the figure and the line agree.
            spentMinor = if (s.spendableThisCycle > 0L) s.usedThisCycle else s.spentThisCycle,
            againstMinor = s.spendableThisCycle.takeIf { it > 0L },
            against = Against.BUDGET,
            used = BudgetPace.usedFraction(s),
            gone = BudgetPace.elapsedFraction(s),
            day = day,
            days = days,
            pendingMinor = 0L,
            projectedMinor = null,
            pace = BudgetPace.of(s),
            currency = s.baseCurrency,
        )
    }

    private fun cardRow(account: AccountEntity, s: BudgetSnapshot): Row? {
        val bill = s.cardBills.firstOrNull { it.cardAccountId == account.id } ?: return null
        val cap = bill.capMinor?.takeIf { it > 0L }
        val close = bill.statementClose
        val next = bill.nextStatementClose
        val days = if (close != null && next != null) ChronoUnit.DAYS.between(close, next).toInt() else null
        val elapsed = bill.statementDaysElapsed
        val used = cap?.let { (bill.unbilledMinor.toFloat() / it).coerceIn(0f, 1f) }
        val gone = if (days != null && days > 0 && elapsed != null) (elapsed.toFloat() / days).coerceIn(0f, 1f) else null
        val over = cap != null && (bill.unbilledMinor >= cap || CreditCardEngine.projectedOverCap(bill))
        return Row(
            accountId = account.id,
            label = account.label,
            kind = Kind.STATEMENT,
            spentMinor = bill.unbilledMinor,
            againstMinor = cap,
            against = if (bill.capSource == CreditCardEngine.CapSource.USER) Against.LIMIT else cap?.let { Against.USUAL },
            used = used,
            gone = gone,
            day = elapsed?.plus(1),
            days = days,
            pendingMinor = bill.pendingMinor,
            projectedMinor = bill.projectedMinor,
            pace = if (over) BudgetPace.Pace.OVER else paceOf(used, gone),
            currency = bill.currency,
            toComeMinor = bill.toComeMinor,
        )
    }

    /**
     * An account the budget does not cover - the joint pot, a second current account - measured
     * against its own habits: the middle of what it spent over the same stretch of the last three
     * cycles.
     */
    private fun cycleRow(account: AccountEntity, s: BudgetSnapshot, transactions: List<TransactionEntity>): Row {
        val start = s.cycleStart
        val end = s.cycleEnd ?: start.plusDays(DEFAULT_CYCLE_DAYS - 1)
        val length = ChronoUnit.DAYS.between(start, end) + 1
        val own = transactions.filter { it.accountId == account.id && it.amountMinor < 0 && !it.isInternalTransfer }
        fun spentBetween(from: LocalDate, to: LocalDate) = own
            .filter { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(from) && !it.isAfter(to) } == true }
        val now = spentBetween(start, s.asOf)
        val earliest = own.mapNotNull { RecurringAnalyzer.parseBookingDate(it.bookingDate) }.minOrNull()
        val past = (1..3).mapNotNull { back ->
            val from = start.minusDays(length * back)
            if (earliest == null || earliest.isAfter(from)) return@mapNotNull null
            spentBetween(from, from.plusDays(length - 1)).sumOf { -it.amountMinor }
        }.filter { it > 0L }.sorted()
        val usual = past.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        val spent = now.sumOf { -it.amountMinor }
        val used = usual?.let { (spent.toFloat() / it).coerceIn(0f, 1f) }
        val gone = BudgetPace.elapsedFraction(s)
        val (day, days) = dayOf(start, s.cycleEnd, s.asOf)
        return Row(
            accountId = account.id,
            label = account.label,
            kind = Kind.CYCLE,
            spentMinor = spent,
            againstMinor = usual,
            against = usual?.let { Against.USUAL },
            used = used,
            gone = gone,
            day = day,
            days = days,
            pendingMinor = now.filter { it.isPending }.sumOf { -it.amountMinor },
            projectedMinor = null,
            pace = if (usual != null && spent >= usual) BudgetPace.Pace.OVER else paceOf(used, gone),
            currency = account.currency,
        )
    }

    /** The same thresholds as the budget's pace, for a line and its tick. */
    fun paceOf(used: Float?, gone: Float?): BudgetPace.Pace {
        if (used == null || gone == null) return BudgetPace.Pace.ON_TRACK
        val ahead = used - gone
        return when {
            ahead > 0.15f -> BudgetPace.Pace.OVER
            ahead > 0.05f -> BudgetPace.Pace.TIGHT
            else -> BudgetPace.Pace.ON_TRACK
        }
    }

    private fun dayOf(start: LocalDate, end: LocalDate?, today: LocalDate): Pair<Int?, Int?> {
        val days = end?.let { ChronoUnit.DAYS.between(start, it).toInt() + 1 }
        val day = (ChronoUnit.DAYS.between(start, today).toInt() + 1).let { d -> days?.let { d.coerceIn(1, it) } ?: d }
        return day to days
    }

    private const val DEFAULT_CYCLE_DAYS = 30L
}
