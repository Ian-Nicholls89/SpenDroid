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
/** What a period in a [CategoryTrend] is measured in. */
enum class TrendBasis {
    /**
     * Pay cycles, compared at the same point through each. Preferred, because everything
     * else in the app is measured this way and because a cycle contains exactly one of each
     * monthly bill however long the month is.
     */
    PAY_CYCLE,

    /** Rolling windows of fixed length, for when no income cycle has been detected. */
    ROLLING_DAYS,
}

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
    val basis: TrendBasis,
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
        val booked = transactions.filter { it.bookingDate.isNotBlank() }

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
     * Each category's spending over a run of comparable periods, ranked by how much it has
     * moved rather than by size - a large category that never changes is not news.
     *
     * Pay cycles where they are known, because the app measures everything else that way and
     * because a cycle holds exactly one of each monthly bill however long the month happens
     * to be. Fixed windows cannot promise that: thirty days drifts against a calendar month,
     * so a bill eventually lands twice in one window and not at all in the next, and the
     * category jumps without anything having changed.
     *
     * The current cycle is only part-run, so past cycles are cut at the same point through -
     * eleven days into a cycle compares against the first eleven days of the ones before it.
     * Cut by proportion rather than by day count, since what is being matched is the set of
     * recurring payments reached by that point, and a shorter cycle reaches them sooner.
     *
     * Falls back to rolling windows when no cycle is known, and returns nothing at all
     * rather than compare two periods of different lengths.
     */
    fun categoryTrends(
        transactions: List<TransactionEntity>,
        userRules: List<CategoryRuleEntity> = emptyList(),
        cardPaymentKeys: Set<String> = emptySet(),
        creditCardAccountIds: Set<String> = emptySet(),
        cycleStarts: List<LocalDate> = emptyList(),
        currentCycleEnd: LocalDate? = null,
        windowDays: Int = 30,
        maxPeriods: Int = 6,
        today: LocalDate = LocalDate.now(),
        minimumMinor: Long = 1000L,
        /**
         * Kept out of the ranking, not out of the budget. A wedding is real money and
         * counts where money is counted - but it goes from nothing to thousands and back,
         * so it would head a list of what is moving every time and drown the habits that
         * list exists to surface.
         */
        exceptional: Set<Category> = setOf(Category.LIFE_EVENTS),
    ): List<CategoryTrend> {
        val dated = transactions
            .filter { it.amountMinor < 0L && it.bookingDate.isNotBlank() }
            .mapNotNull { tx ->
                RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { date -> date to tx }
            }
            .filter { !it.first.isAfter(today) }
        if (dated.isEmpty()) return emptyList()

        val earliest = dated.minOf { it.first }
        val cyclePeriods = cyclePeriods(cycleStarts, currentCycleEnd, today, earliest, maxPeriods)
        val basis = if (cyclePeriods != null) TrendBasis.PAY_CYCLE else TrendBasis.ROLLING_DAYS
        val periods = cyclePeriods
            ?: rollingPeriods(earliest, today, windowDays, maxPeriods)
            ?: return emptyList()

        val spanDays = periods.last().let {
            ChronoUnit.DAYS.between(it.first, it.second).toInt() + 1
        }
        val currency = dated.first().second.currency

        return dated
            .filter { (date, _) -> !date.isBefore(periods.first().first) }
            .groupBy { (_, tx) ->
                CategoryEngine.classify(tx, userRules, cardPaymentKeys, creditCardAccountIds)
            }
            .filterKeys { it !in exceptional }
            .mapNotNull { (category, rows) ->
                val series = periods.map { (from, to) ->
                    rows
                        .filter { (date, _) -> !date.isBefore(from) && !date.isAfter(to) }
                        .sumOf { (_, tx) -> -tx.amountMinor }
                }
                // A category that barely registers is noise, not a trend.
                if (series.max() < minimumMinor) return@mapNotNull null
                CategoryTrend(
                    category = category,
                    seriesMinor = series,
                    windowDays = spanDays,
                    basis = basis,
                    firstMinor = series.first(),
                    lastMinor = series.last(),
                    currency = currency,
                )
            }
            // Biggest movers first, in either direction; a category with nothing to compare
            // against sinks to the bottom rather than being dropped.
            .sortedByDescending { it.change?.let { c -> kotlin.math.abs(c) } ?: -1f }
    }

    /**
     * Each cycle cut at the same proportion through as the current one has run.
     *
     * Null when there is no run of cycles to compare, which sends the caller to fixed
     * windows rather than to a comparison between periods of different lengths.
     */
    private fun cyclePeriods(
        cycleStarts: List<LocalDate>,
        currentCycleEnd: LocalDate?,
        today: LocalDate,
        earliest: LocalDate,
        maxPeriods: Int,
    ): List<Pair<LocalDate, LocalDate>>? {
        val starts = cycleStarts.distinct().sorted().filter { !it.isAfter(today) }
        if (starts.size < 2) return null

        val currentStart = starts.last()
        val currentLength = ChronoUnit.DAYS
            .between(currentStart, currentCycleEnd ?: today)
            .toInt() + 1
        if (currentLength <= 0) return null
        val elapsed = ChronoUnit.DAYS.between(currentStart, today).toInt() + 1
        val fraction = (elapsed.toFloat() / currentLength.toFloat()).coerceIn(0f, 1f)

        // Only cycles the data covers from their first day; a cycle the history starts
        // part way through would be short for a reason that has nothing to do with spending.
        val usable = starts.dropLast(1).filter { !it.isBefore(earliest) }
        if (usable.isEmpty()) return null

        val periods = usable.takeLast(maxPeriods - 1).map { start ->
            val next = starts[starts.indexOf(start) + 1]
            val length = ChronoUnit.DAYS.between(start, next).toInt()
            val days = Math.round(length * fraction).coerceIn(1, length)
            start to start.plusDays(days - 1L)
        }
        return periods + (currentStart to today)
    }

    /** Whole fixed-length windows ending today, oldest first, or null if fewer than two. */
    private fun rollingPeriods(
        earliest: LocalDate,
        today: LocalDate,
        windowDays: Int,
        maxPeriods: Int,
    ): List<Pair<LocalDate, LocalDate>>? {
        val daysHeld = ChronoUnit.DAYS.between(earliest, today).toInt() + 1
        val windows = (daysHeld / windowDays).coerceAtMost(maxPeriods)
        // One period is a figure, not a trend, and half a period is a misleading one.
        if (windows < 2) return null
        return (0 until windows).map { index ->
            val end = today.minusDays((windows - 1 - index).toLong() * windowDays)
            end.minusDays(windowDays - 1L) to end
        }
    }

    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
}