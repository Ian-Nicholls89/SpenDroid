package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Where an account's balance is heading, day by day: what's in it now, less the bills, card
 * bills and transfers known to leave it, plus the pay and transfers known to arrive, less its
 * usual day-to-day spending. Nothing new is guessed - every payment is one the budget already
 * expects - so the line moves only as the regular payments and the habits behind it do.
 */
object Forecast {

    enum class Kind { BILL, CARD_BILL, INCOME, TRANSFER_IN, TRANSFER_OUT }

    /** Something known to land on a day. [amountMinor] is signed: money in is positive. */
    data class Event(val date: LocalDate, val label: String, val amountMinor: Long, val kind: Kind)

    /** A day's closing balance and what moved it. */
    data class Day(val date: LocalDate, val balanceMinor: Long, val events: List<Event>)

    data class Result(
        val accountId: String,
        val startMinor: Long,
        val days: List<Day>,
        /** Usual day-to-day spending, a positive figure, taken off each day ahead. */
        val dailySpendMinor: Long,
    ) {
        val events: List<Event> get() = days.flatMap { it.events }
        val lowest: Day get() = days.minBy { it.balanceMinor }

        /** The lowest point strictly before [date] - "before payday" - or the lowest of all. */
        fun lowestBefore(date: LocalDate?): Day =
            days.filter { date == null || it.date.isBefore(date) }.minByOrNull { it.balanceMinor } ?: lowest

        /** The first day it closes below zero, if it does. */
        val firstBelowZero: Day? get() = days.firstOrNull { it.balanceMinor < 0 }
    }

    /** When the balance would go below zero, said plainly: which day, by how much, and why. */
    data class Warning(
        val account: AccountEntity,
        val on: LocalDate,
        val shortMinor: Long,
        val cause: Event?,
        /** What moved in before [on] would cover it, rounded up to the next £10. */
        val coverMinor: Long,
        /** When money next comes in after the dip, if it does. */
        val nextIn: Event?,
    )

    /** Accounts a balance can be forecast for: money you hold, not cards or PayPal's pass-through. */
    fun forecastable(accounts: List<AccountEntity>): List<AccountEntity> =
        accounts.filter { it.balanceMinor != null && it.accountType != AccountType.CREDIT_CARD && it.accountType != AccountType.PAYPAL }

    /**
     * [account]'s balance from [today] to [until]. [rules] are every regular payment - detected
     * and added by hand - and [ignored] the ones switched off. Null when there's no balance to
     * start from.
     */
    fun forAccount(
        account: AccountEntity,
        accounts: List<AccountEntity>,
        rules: List<RecurringRule>,
        ignored: Set<String>,
        snapshot: BudgetSnapshot?,
        transactions: List<TransactionEntity>,
        today: LocalDate,
        until: LocalDate,
        calendar: WorkingDayCalendar = WorkingDayCalendar(),
        overrides: Map<String, com.spendroid.data.db.RuleOverrideEntity> = emptyMap(),
    ): Result? {
        val start = account.balanceMinor ?: return null
        if (until.isBefore(today)) return null
        val events = mutableListOf<Event>()

        // Regular payments in and out of this account. A manual payment named for no account is
        // the budget's, so it leaves the account the budget is paid from.
        val ownTx = transactions.filter { it.accountId == account.id }
        // A card bill paid by direct debit can be detected as a regular payment too; the card bill
        // below is that payment, so it isn't taken twice.
        val cardPayees = ownTx.filter { "${it.accountId}|${it.transactionId}" in snapshot?.cardPaymentKeys.orEmpty() }
            .map { RecurringAnalyzer.normalizedPayee(it.payee) }.toSet()
        val mine = rules.filter { rule ->
            rule.key !in ignored && !rule.key.startsWith(CARD_BILL_KEY_PREFIX) && !stale(rule, today) &&
                RecurringAnalyzer.normalizedPayee(rule.payee) !in cardPayees &&
                (rule.paidFrom ?: rule.accountIds.singleOrNull() ?: (if (rule.isManual) snapshot?.potAccountId else null)) == account.id
        }
        // Moved off weekends and bank holidays the way the budget moves them, so pay due on a
        // Sunday lands on the Friday here too.
        fun next(rule: RecurringRule, after: LocalDate) = RecurringAnalyzer.nextOccurrence(
            rule, after, calendar, PaymentShift.from(overrides[rule.key]?.shift) ?: PaymentShift.defaultFor(rule.direction),
            overrides[rule.key]?.anchorDay, overrides[rule.key]?.decemberAnchorDay,
        )
        for (rule in mine) {
            var due = next(rule, today.minusDays(1))
            var guard = 0
            while (!due.isAfter(until) && guard++ < 400) {
                // Due about now and already gone: it's in the balance, so not taken again.
                val alreadyGone = !due.isAfter(today.plusDays(SEEN_DAYS)) && ownTx.any { tx ->
                    (tx.amountMinor < 0) == (rule.amountMinor < 0) &&
                        RecurringAnalyzer.normalizedPayee(tx.payee) == RecurringAnalyzer.normalizedPayee(rule.payee) &&
                        RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(due.minusDays(SEEN_DAYS)) && !it.isAfter(today) } == true
                }
                if (!alreadyGone) {
                    val kind = when {
                        rule.direction == Direction.IN && rule.internalTransfer -> Kind.TRANSFER_IN
                        rule.direction == Direction.IN -> Kind.INCOME
                        rule.internalTransfer -> Kind.TRANSFER_OUT
                        else -> Kind.BILL
                    }
                    events += Event(due, rule.payee, if (rule.direction == Direction.IN) abs(rule.amountMinor) else -abs(rule.amountMinor), kind)
                }
                due = next(rule, due)
            }
        }

        // Card bills paid from this account: the statement now owed, then the spending already on
        // the next one, then the usual bill each month after.
        val cards = accounts.filter { it.accountType == AccountType.CREDIT_CARD && (it.linkedCreditCardAccountId ?: snapshot?.potAccountId) == account.id }
        for (card in cards) {
            val bill = snapshot?.cardBills?.firstOrNull { it.cardAccountId == card.id } ?: continue
            var due = bill.dueDate ?: continue
            var amount = if (!bill.statementPaid && bill.dueMinor > 0L) bill.dueMinor else 0L
            if (due.isBefore(today)) {
                if (amount > 0L) events += Event(today, bill.cardLabel, -amount, Kind.CARD_BILL)
                due = due.plusMonths(1)
                amount = bill.unbilledMinor + bill.toComeMinor
            }
            var month = 0
            while (!due.isAfter(until) && month++ < 24) {
                if (amount > 0L) events += Event(due, bill.cardLabel, -amount, Kind.CARD_BILL)
                due = due.plusMonths(1)
                amount = if (month == 1) bill.unbilledMinor + bill.toComeMinor else usualBill(bill)
            }
        }

        val daily = dailySpend(ownTx, mine, snapshot?.cardPaymentKeys.orEmpty(), today)
        val byDay = events.groupBy { it.date }
        val days = mutableListOf<Day>()
        var balance = start
        var date = today
        while (!date.isAfter(until)) {
            if (date.isAfter(today)) balance -= daily
            val todays = byDay[date].orEmpty().sortedBy { it.amountMinor }
            balance += todays.sumOf { it.amountMinor }
            days += Day(date, balance, todays)
            date = date.plusDays(1)
        }
        return Result(account.id, start, days, daily)
    }

    /** Every account that would close a day below zero before [until], with what causes it. */
    fun warnings(
        accounts: List<AccountEntity>,
        rules: List<RecurringRule>,
        ignored: Set<String>,
        snapshot: BudgetSnapshot?,
        transactions: List<TransactionEntity>,
        today: LocalDate,
        until: LocalDate,
        calendar: WorkingDayCalendar = WorkingDayCalendar(),
        overrides: Map<String, com.spendroid.data.db.RuleOverrideEntity> = emptyMap(),
    ): List<Warning> = forecastable(accounts).mapNotNull { account ->
        // Already below zero isn't news, and the bank's own overdraft rules decide what happens.
        if ((account.balanceMinor ?: 0L) < 0L) return@mapNotNull null
        val result = forAccount(account, accounts, rules, ignored, snapshot, transactions, today, until, calendar, overrides) ?: return@mapNotNull null
        val first = result.firstBelowZero ?: return@mapNotNull null
        // Only a dip a known payment causes: day-to-day spending alone running it down is the
        // budget's to say, not a warning's.
        val cause = result.days.filter { !it.date.isAfter(first.date) }.flatMap { it.events }
            .filter { it.amountMinor < 0 }.maxByOrNull { -it.amountMinor + if (it.date == first.date) Long.MAX_VALUE / 2 else 0 }
            ?: return@mapNotNull null
        val deepest = result.days.dropWhile { it.date.isBefore(first.date) }
            .takeWhile { it.balanceMinor < 0 }.minOf { it.balanceMinor }
        val nextIn = result.events.firstOrNull { it.date.isAfter(first.date) && it.amountMinor > 0 }
        Warning(account, first.date, -deepest, cause, roundUpTen(-deepest), nextIn)
    }

    private fun roundUpTen(minor: Long): Long = ((minor + 999) / 1000) * 1000

    /** A rule not seen for over two of its gaps is no longer coming. */
    private fun stale(rule: RecurringRule, today: LocalDate): Boolean {
        if (rule.isManual) return false
        val gap = when (rule.cadence) {
            Cadence.WEEKLY -> 7L
            Cadence.FORTNIGHTLY -> 14L
            Cadence.MONTHLY, Cadence.MONTHLY_LAST_DAY, Cadence.MONTHLY_LAST_BUSINESS_DAY -> 31L
            Cadence.QUARTERLY -> 92L
            Cadence.ANNUAL -> 366L
        }
        return ChronoUnit.DAYS.between(rule.lastOccurrence, today) > gap * 2 + 7
    }

    private fun usualBill(bill: CreditCardEngine.CardBill): Long =
        bill.pastBills.map { it.second }.filter { it > 0L }.takeLast(3).takeIf { it.isNotEmpty() }?.average()?.toLong()
            ?: bill.dueMinor.coerceAtLeast(0L)

    /**
     * Usual day-to-day spending from the account: the last 90 days' money out, less its regular
     * payments, card bills and transfers - those are forecast one by one already.
     */
    internal fun dailySpend(own: List<TransactionEntity>, rules: List<RecurringRule>, cardPaymentKeys: Set<String>, today: LocalDate): Long {
        val from = today.minusDays(WINDOW_DAYS)
        val regular = rules.map { RecurringAnalyzer.normalizedPayee(it.payee) }.toSet()
        val dated = own.mapNotNull { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { it to tx } }
            .filter { (d, _) -> d.isAfter(from) && !d.isAfter(today) }
        if (dated.isEmpty()) return 0L
        val spent = dated.filter { (_, tx) ->
            tx.amountMinor < 0 && !tx.isInternalTransfer && !tx.isRecurring &&
                "${tx.accountId}|${tx.transactionId}" !in cardPaymentKeys &&
                RecurringAnalyzer.normalizedPayee(tx.payee) !in regular
        }.sumOf { (_, tx) -> -tx.amountMinor }
        val span = ChronoUnit.DAYS.between(dated.minOf { it.first }, today).coerceIn(14L, WINDOW_DAYS)
        return spent / span
    }

    private const val WINDOW_DAYS = 90L
    private const val SEEN_DAYS = 3L
}
