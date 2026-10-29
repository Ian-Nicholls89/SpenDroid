package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The last pay cycle, looked back on: what was left over, how each day went against its share,
 * and a few facts. Built from the budget as it stood at the end of each day, so it agrees with
 * what Home said at the time.
 */
data class CycleReview(
    val start: LocalDate,
    val end: LocalDate,
    val currency: String,
    /** What was left to spend when the cycle ended - carried into the next. Negative when over. */
    val leftOverMinor: Long,
    val spentMinor: Long,
    /** Spending day by day, and each day's even share of the cycle's budget. */
    val daily: List<Pair<LocalDate, Long>>,
    val dailyShareMinor: Long,
    /** Days that ended in step with the cycle, or ahead of it. */
    val daysAhead: Int,
    val biggestDay: Pair<LocalDate, Long>?,
    /** The place paid most often, and how many times. */
    val mostVisited: Pair<String, Int>?,
) {
    val days: Int get() = daily.size

    /** A headline for the cycle, from how it went. */
    val headline: String
        get() {
            val share = if (days == 0) 0f else daysAhead.toFloat() / days
            return when {
                leftOverMinor <= 0L -> "A tight one"
                share >= 0.75f -> "A cycle of quiet discipline"
                share >= 0.4f -> "Made it through, with some to spare"
                else -> "Close, but made it"
            }
        }

    /** The line under the headline. */
    val standfirst: String
        get() {
            val pace = "Ahead of the days on $daysAhead of $days."
            val big = biggestDay?.takeIf { dailyShareMinor > 0 && it.second > dailyShareMinor * 5 / 2 }
            val dayName = big?.first?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.UK)
            return when {
                big == null -> pace
                daysAhead * 2 >= days -> "$pace One big $dayName, then back on course."
                else -> "$pace A big $dayName didn't help."
            }
        }

    companion object {
        /** The place paid most often between [start] and [end]: everyday spending, not bills or transfers. */
        fun mostVisited(transactions: List<TransactionEntity>, start: LocalDate, end: LocalDate): Pair<String, Int>? =
            transactions
                .filter { tx ->
                    tx.amountMinor < 0 && !tx.isInternalTransfer && !tx.isRecurring &&
                        RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(start) && !it.isAfter(end) } == true
                }
                .groupBy { RecurringAnalyzer.normalizedPayee(it.payee) }
                .filterKeys { it.isNotBlank() }
                .maxByOrNull { it.value.size }
                ?.takeIf { it.value.size >= 2 }
                ?.let { (_, txs) -> txs.first().payee to txs.size }
    }
}
