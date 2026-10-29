package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * When a regular bill's amount changes: what it was, what it is now, since when, and what that
 * comes to over a year. Read from the bill's own payments, so a rise is a payment that came out
 * at the new amount, not a guess.
 */
object PriceChanges {

    /** One payment of a bill, as a positive amount. */
    data class Payment(val date: LocalDate, val amountMinor: Long)

    data class Change(
        val ruleKey: String,
        val payee: String,
        val beforeMinor: Long,
        val afterMinor: Long,
        /** The first payment at the new amount. */
        val on: LocalDate,
        /** When it first came out at the old amount, for "it's been £31.50 since April". */
        val since: LocalDate,
        val cadence: Cadence,
        /** Every payment, oldest first. */
        val history: List<Payment>,
    ) {
        val deltaMinor: Long get() = afterMinor - beforeMinor
        val up: Boolean get() = deltaMinor > 0
        val percent: Double get() = if (beforeMinor == 0L) 0.0 else deltaMinor * 100.0 / beforeMinor
        val perYearMinor: Long get() = deltaMinor * timesAYear(cadence)

        /** "rose" and "fell" are the same event, said once, keyed so a decision sticks to it. */
        val key: String get() = "$ruleKey|$on"
    }

    /** How big a change is worth a notification. */
    enum class Threshold(val label: String) {
        ANY("Any"), PCT2("2%"), PCT5("5%"), POUND1("£1"), POUNDS5("£5");

        fun passes(change: Change): Boolean = when (this) {
            ANY -> change.deltaMinor != 0L
            PCT2 -> abs(change.percent) >= 2.0
            PCT5 -> abs(change.percent) >= 5.0
            POUND1 -> abs(change.deltaMinor) >= 100
            POUNDS5 -> abs(change.deltaMinor) >= 500
        }

        companion object {
            fun from(name: String?) = entries.firstOrNull { it.name == name } ?: PCT2
        }
    }

    /**
     * A bill's payments: the same payee, the same way, booked, and of the same order as the bill
     * - half to double, so a refund or a one-off extra from the same company isn't one. Payments
     * split on one day (two children's £50) count as one.
     */
    fun history(rule: RecurringRule, transactions: List<TransactionEntity>): List<Payment> {
        val payee = RecurringAnalyzer.normalizedPayee(rule.payee)
        if (payee.isBlank()) return emptyList()
        val usual = abs(rule.amountMinor)
        return transactions.asSequence()
            .filter { !it.isPending && !it.isInternalTransfer && it.amountMinor < 0 && it.currency == rule.currency }
            .filter { RecurringAnalyzer.normalizedPayee(it.payee) == payee }
            .mapNotNull { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { it to -tx.amountMinor } }
            .groupBy({ it.first }, { it.second })
            .map { (date, amounts) -> Payment(date, amounts.sum()) }
            .filter { it.amountMinor * 2 >= usual && it.amountMinor <= usual * 2 }
            .sortedBy { it.date }
            .toList()
    }

    /** The latest change in a bill's amount within the last year, or null if it hasn't changed. */
    fun latest(rule: RecurringRule, transactions: List<TransactionEntity>, today: LocalDate): Change? {
        val history = history(rule, transactions)
        if (history.size < 2) return null
        for (i in history.indices.reversed()) {
            if (i == 0) break
            val now = history[i].amountMinor
            val before = history[i - 1].amountMinor
            if (now == before) continue
            if (ChronoUnit.DAYS.between(history[i].date, today) > YEAR_DAYS) return null
            var first = i - 1
            while (first > 0 && history[first - 1].amountMinor == before) first--
            return Change(rule.key, rule.payee, before, now, history[i].date, history[first].date, rule.cadence, history)
        }
        return null
    }

    /**
     * The latest change for each regular bill - money out, not card bills, not transfers, and
     * not bills whose amount varies anyway unless [includeVariable].
     */
    fun all(rules: List<RecurringRule>, transactions: List<TransactionEntity>, today: LocalDate, includeVariable: Boolean = false): Map<String, Change> =
        rules.asSequence()
            .filter { it.direction == Direction.OUT && !it.internalTransfer && !it.key.startsWith(CARD_BILL_KEY_PREFIX) }
            .filter { includeVariable || !it.isVariable }
            // A rise can be detected as a new bill beside the old: one payee, said once, by the latest.
            .sortedByDescending { it.lastOccurrence }
            .distinctBy { RecurringAnalyzer.normalizedPayee(it.payee) }
            .mapNotNull { rule -> latest(rule, transactions, today)?.let { rule.key to it } }
            .toMap()

    private fun timesAYear(cadence: Cadence): Long = when (cadence) {
        Cadence.WEEKLY -> 52
        Cadence.FORTNIGHTLY -> 26
        Cadence.MONTHLY, Cadence.MONTHLY_LAST_DAY, Cadence.MONTHLY_LAST_BUSINESS_DAY -> 12
        Cadence.QUARTERLY -> 4
        Cadence.ANNUAL -> 1
    }

    private const val YEAR_DAYS = 366L
}
