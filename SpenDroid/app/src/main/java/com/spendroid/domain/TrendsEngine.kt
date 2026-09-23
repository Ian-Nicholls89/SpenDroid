package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.YearMonth
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
 * One category's run of monthly totals, and how far it has moved across them.
 *
 * A total going up says nothing about why. This is the answer to that: the same months,
 * split by where the money went.
 */
data class CategoryTrend(
    val category: Category,
    /** Oldest first, one entry per month in the window, zero where nothing was spent. */
    val monthlyMinor: List<Long>,
    val firstMinor: Long,
    val lastMinor: Long,
    val currency: String,
) {
    /** Change from the first month to the last, as a fraction. Null when it started at zero. */
    val change: Float?
        get() = if (firstMinor <= 0L) null else (lastMinor - firstMinor).toFloat() / firstMinor
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
     * Each category's monthly run over the same window [analyze] uses, ranked by how much it
     * has moved rather than by size - a large category that never changes is not news.
     *
     * Months where a category was untouched are zeros rather than gaps, so every series has
     * the same length and the same x positions.
     */
    fun categoryTrends(
        transactions: List<TransactionEntity>,
        userRules: List<CategoryRuleEntity> = emptyList(),
        cardPaymentKeys: Set<String> = emptySet(),
        creditCardAccountIds: Set<String> = emptySet(),
        monthsToShow: Int = 6,
        minimumMonthlyMinor: Long = 1000L,
    ): List<CategoryTrend> {
        val dated = transactions
            .filter { !it.isPending && it.amountMinor < 0L && it.bookingDate.isNotBlank() }
            .mapNotNull { tx ->
                runCatching { YearMonth.parse(tx.bookingDate.substring(0, 7), monthFormatter) }
                    .getOrNull()
                    ?.let { month -> Triple(month, tx, CategoryEngine.classify(tx, userRules, cardPaymentKeys, creditCardAccountIds)) }
            }
        if (dated.isEmpty()) return emptyList()

        val months = dated.map { it.first }.distinct().sorted().takeLast(monthsToShow)
        if (months.size < 2) return emptyList()

        val currency = dated.firstOrNull()?.second?.currency ?: "GBP"

        return dated
            .filter { it.first in months }
            .groupBy { it.third }
            .mapNotNull { (category, rows) ->
                val byMonth = rows.groupBy { it.first }
                val series = months.map { month ->
                    byMonth[month].orEmpty().sumOf { -it.second.amountMinor }
                }
                // A category that barely registers is noise, not a trend.
                if (series.max() < minimumMonthlyMinor) return@mapNotNull null
                CategoryTrend(
                    category = category,
                    monthlyMinor = series,
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