package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

data class MonthSummary(
    val month: YearMonth,
    val income: Long,
    val spending: Long,
    val savings: Long,
    val savingsRate: Float,
    val currency: String,
)

/**
 * One category's run of equal-length periods, and how far it has moved across them.
 *
 * A total going up says nothing about why. This is the answer to that: the same stretch of
 * time, split by where the money went.
 */
data class CategoryTrend(
    val category: Category,
    /**
     * Oldest first. Every entry covers the same number of days, which is the whole point:
     * calendar months do not, so a flat category compared across a 5-day month and a 31-day
     * one appears to have risen sixfold without anything having changed.
     */
    val seriesMinor: List<Long>,
    /** How many days each entry covers. */
    val windowDays: Int,
    val firstMinor: Long,
    val lastMinor: Long,
    val currency: String,
) {
    /** Change from the first period to the last, as a fraction. Null when it started at zero. */
    val change: Float?
        get() = if (firstMinor <= 0L) null else (lastMinor - firstMinor).toFloat() / firstMinor

    /** How far back the comparison reaches. */
    val spanDays: Int get() = windowDays * (seriesMinor.size - 1)
}

data class TrendSummary(
    val months: List<MonthSummary>,
    val avgIncome: Long,
    val avgSpending: Long,
    val avgSavingsRate: Float,
)

object TrendsEngine {

    fun analyze(transactions: List<TransactionEntity>): TrendSummary {
        val booked = transactions.filter { !it.isPending && it.bookingDate.isNotBlank() }

        // Pair each transaction with its month first, so rows with an unparseable date drop
        // out before grouping and the map never has a nullable key to cast away.
        val byMonth: Map<YearMonth, List<TransactionEntity>> = booked
            .mapNotNull { tx ->
                runCatching { YearMonth.parse(tx.bookingDate.substring(0, 7), monthFormatter) }
                    .getOrNull()
                    ?.let { month -> month to tx }
            }
            .groupBy({ it.first }, { it.second })

        val months = byMonth.keys.sorted().takeLast(12)

        val summaries = months.map { month ->
            val txs = byMonth[month] ?: emptyList()
            val income = txs.filter { it.amountMinor > 0 }.sumOf { it.amountMinor }
            val spending = txs.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }
            val savings = income - spending
            val rate = if (income > 0) savings.toFloat() / income.toFloat() else 0f
            val dominant = txs.maxByOrNull { kotlin.math.abs(it.amountMinor * 2L) }.let { largest ->
                largest?.currency ?: txs.firstOrNull()?.currency ?: "GBP"
            }
            MonthSummary(month, income, spending, savings, rate, dominant)
        }

        val count = summaries.size.coerceAtLeast(1)
        val avgIncome = summaries.sumOf { it.income } / count
        val avgSpending = summaries.sumOf { it.spending } / count
        val avgSavingsRate = if (summaries.isNotEmpty()) summaries.map { it.savingsRate }.average().toFloat() else 0f

        return TrendSummary(summaries, avgIncome, avgSpending, avgSavingsRate)
    }

    /**
     * Each category's spending over a run of equal-length periods, ranked by how much it has
     * moved rather than by size - a large category that never changes is not news.
     *
     * Deliberately not calendar months. The API returns ninety days, so the oldest month is
     * a stub of a few days and the current one is however far through it happens to be;
     * comparing across them made a flat category look like it had doubled. Rolling windows
     * anchored on today are all the same length, so the only thing a change can mean is that
     * spending changed.
     *
     * Only whole windows the data actually covers are used, so the first point is never a
     * partial period pretending to be a full one.
     */
    fun categoryTrends(
        transactions: List<TransactionEntity>,
        userRules: List<CategoryRuleEntity> = emptyList(),
        cardPaymentKeys: Set<String> = emptySet(),
        creditCardAccountIds: Set<String> = emptySet(),
        windowDays: Int = 30,
        maxWindows: Int = 6,
        today: LocalDate = LocalDate.now(),
        minimumMinor: Long = 1000L,
    ): List<CategoryTrend> {
        val dated = transactions
            .filter { !it.isPending && it.amountMinor < 0L && it.bookingDate.isNotBlank() }
            .mapNotNull { tx ->
                RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { date -> date to tx }
            }
            .filter { !it.first.isAfter(today) }
        if (dated.isEmpty()) return emptyList()

        val earliest = dated.minOf { it.first }
        val daysHeld = ChronoUnit.DAYS.between(earliest, today).toInt() + 1
        val windows = (daysHeld / windowDays).coerceAtMost(maxWindows)
        // One window is a figure, not a trend, and half a window is a misleading one.
        if (windows < 2) return emptyList()

        // Window 0 is the oldest of those used; the newest always ends today.
        val bounds = (0 until windows).map { index ->
            val endOffset = (windows - 1 - index).toLong() * windowDays
            val end = today.minusDays(endOffset)
            val start = end.minusDays(windowDays - 1L)
            start to end
        }

        val currency = dated.first().second.currency

        return dated
            .filter { (date, _) -> !date.isBefore(bounds.first().first) }
            .groupBy { (_, tx) ->
                CategoryEngine.classify(tx, userRules, cardPaymentKeys, creditCardAccountIds)
            }
            .mapNotNull { (category, rows) ->
                val series = bounds.map { (start, end) ->
                    rows
                        .filter { (date, _) -> !date.isBefore(start) && !date.isAfter(end) }
                        .sumOf { (_, tx) -> -tx.amountMinor }
                }
                // A category that barely registers is noise, not a trend.
                if (series.max() < minimumMinor) return@mapNotNull null
                CategoryTrend(
                    category = category,
                    seriesMinor = series,
                    windowDays = windowDays,
                    firstMinor = series.first(),
                    lastMinor = series.last(),
                    currency = currency,
                )
            }
            // Biggest movers first, in either direction; a category with nothing to compare
            // against sinks to the bottom rather than being dropped.
            .sortedByDescending { it.change?.let { c -> kotlin.math.abs(c) } ?: -1f }
    }

    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
}