package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * A bill's contract, from the one thing the user says - when it ends: how long is left, and once
 * it has ended, whether the bill went up. Kept by payee, not by the bill's own key: a bill that
 * goes up can be detected afresh at its new amount, and that rise is the very thing being watched.
 */
object Contracts {

    /** Where a contract stands. */
    sealed interface Status {
        val end: LocalDate

        /** More than six weeks to go. */
        data class Running(override val end: LocalDate, val daysLeft: Long) : Status

        /** In its last six weeks. */
        data class EndingSoon(override val end: LocalDate, val daysLeft: Long) : Status

        /** Ended, and the bill is what it was - or there's been no payment since to tell. */
        data class Ended(override val end: LocalDate) : Status

        /** Ended, and the bill has gone up since. */
        data class EndedUp(override val end: LocalDate, val beforeMinor: Long, val afterMinor: Long, val since: LocalDate) : Status {
            val upMinor: Long get() = afterMinor - beforeMinor
        }
    }

    /** A bill with an end date: whose, what it is now, and where its contract stands. */
    data class Entry(val key: String, val payee: String, val currency: String, val amountMinor: Long, val status: Status)

    /** The last six weeks: when it's worth looking for a better deal. */
    const val SOON_DAYS = 42L

    /** The key a contract is kept under: the payee, however the bank spaces or cases it. */
    fun keyFor(payee: String): String = "payee:" + RecurringAnalyzer.normalizedPayee(payee)

    /**
     * Where [rule]'s contract stands, ending on [end]. Ended, the bill "went up" when a payment
     * after the end is higher than the last one before it - by more than pennies.
     */
    fun status(rule: RecurringRule, end: LocalDate, transactions: List<TransactionEntity>, today: LocalDate): Status {
        val daysLeft = ChronoUnit.DAYS.between(today, end)
        if (daysLeft > SOON_DAYS) return Status.Running(end, daysLeft)
        if (daysLeft >= 0) return Status.EndingSoon(end, daysLeft)
        val payments = payments(rule, transactions)
        val before = payments.lastOrNull { !it.date.isAfter(end) } ?: return Status.Ended(end)
        val after = payments.filter { it.date.isAfter(end) }
        val latest = after.lastOrNull() ?: return Status.Ended(end)
        val rise = latest.amountMinor - before.amountMinor
        return if (rise >= MIN_RISE_MINOR && rise * 100 >= before.amountMinor) {
            Status.EndedUp(end, before.amountMinor, latest.amountMinor, after.first { it.amountMinor > before.amountMinor }.date)
        } else {
            Status.Ended(end)
        }
    }

    /** The bill's payments to this payee, by date, of the bill's order of size. */
    private fun payments(rule: RecurringRule, transactions: List<TransactionEntity>): List<PriceChanges.Payment> {
        // Its own key may have moved on with the rise, so the payee's payments, not the rule's.
        val usual = abs(rule.amountMinor)
        val payee = RecurringAnalyzer.normalizedPayee(rule.payee)
        return transactions.asSequence()
            .filter { !it.isPending && !it.isInternalTransfer && it.amountMinor < 0 && RecurringAnalyzer.normalizedPayee(it.payee) == payee }
            .mapNotNull { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { PriceChanges.Payment(it, -tx.amountMinor) } }
            .filter { it.amountMinor * 3 >= usual && it.amountMinor <= usual * 3 }
            .sortedBy { it.date }
            .toList()
    }

    /**
     * 12, 18 and 24 months from when payments started: the usual contract lengths, as guesses -
     * ones already past too, for a contract that has already ended.
     */
    fun guesses(rule: RecurringRule, transactions: List<TransactionEntity>, @Suppress("UNUSED_PARAMETER") today: LocalDate): List<Pair<Int, LocalDate>> {
        val start = payments(rule, transactions).firstOrNull()?.date ?: return emptyList()
        return listOf(12, 18, 24).map { it to start.plusMonths(it.toLong()) }
    }

    /** When payments to this payee started, if any are stored. */
    fun started(rule: RecurringRule, transactions: List<TransactionEntity>): LocalDate? = payments(rule, transactions).firstOrNull()?.date

    /**
     * Every bill with an end date, where it stands. [ends] by [keyFor]; one the user is happy with
     * at its new price, in [accepted] by the same key and its end date, says nothing more.
     */
    fun all(
        rules: List<RecurringRule>,
        transactions: List<TransactionEntity>,
        ends: Map<String, LocalDate>,
        accepted: Set<String>,
        today: LocalDate,
    ): Map<String, Entry> {
        if (ends.isEmpty()) return emptyMap()
        return rules.filter { it.direction == Direction.OUT }
            .sortedByDescending { it.lastOccurrence }
            .distinctBy { keyFor(it.payee) }
            .mapNotNull { rule ->
                val key = keyFor(rule.payee)
                val end = ends[key] ?: return@mapNotNull null
                val status = status(rule, end, transactions, today)
                // Happy with the price, or the bill has stopped - switched away - and it's settled.
                val settled = acceptedKey(key, end) in accepted ||
                    (status !is Status.Running && status !is Status.EndingSoon && Subscriptions.lapsed(rule, today))
                key to Entry(key, rule.payee, rule.currency, abs(rule.amountMinor), if (settled && status is Status.EndedUp) Status.Ended(end) else status)
            }
            .toMap()
    }

    fun acceptedKey(key: String, end: LocalDate) = "$key|$end"

    /** Up by at least 50p, and at least 1%: rounding isn't a rise. */
    private const val MIN_RISE_MINOR = 50L
}
