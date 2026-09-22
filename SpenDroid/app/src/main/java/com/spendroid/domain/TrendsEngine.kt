package com.spendroid.domain

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

data class TrendSummary(
    val months: List<MonthSummary>,
    val avgIncome: Long,
    val avgSpending: Long,
    val avgSavingsRate: Float,
)

object TrendsEngine {

    fun analyze(transactions: List<TransactionEntity>): TrendSummary {
        val booked = transactions.filter { !it.isPending && it.bookingDate.isNotBlank() }

        val byMonth = booked.groupBy { tx ->
            runCatching { YearMonth.parse(tx.bookingDate.substring(0, 7), monthFormatter) }.getOrNull()
        }.filterKeys { it != null } as Map<YearMonth, List<TransactionEntity>>

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

    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
}