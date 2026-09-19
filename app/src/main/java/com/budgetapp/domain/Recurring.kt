package com.budgetapp.domain

import java.time.LocalDate

enum class Direction { IN, OUT }

enum class Cadence {
    WEEKLY,
    FORTNIGHTLY,
    MONTHLY,
    MONTHLY_LAST_DAY,
    MONTHLY_LAST_BUSINESS_DAY,
    QUARTERLY,
    ANNUAL,
}

data class RecurringRule(
    val key: String,
    val payee: String,
    val direction: Direction,
    val amountMinor: Long,
    val currency: String,
    val cadence: Cadence,
    val anchorDay: Int,
    val lastOccurrence: LocalDate,
    val occurrences: Int,
    val score: Float,
)

data class UpcomingPayment(
    val rule: RecurringRule,
    val dueDate: LocalDate,
    val amountMinor: Long,
)

data class BudgetSnapshot(
    val averageMonthlyIncome: Long,
    val fixedMonthlyOutgoings: Long,
    val variableMonthlyBudget: Long,
    val nextIncomeDate: LocalDate?,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate?, // end of current pay cycle
    val spentThisCycle: Long,
    val spentToday: Long,
    val upcomingFixed: List<UpcomingPayment>,
    val availableToSpend: Long,
    val incomeRules: List<RecurringRule>,
    val fixedRules: List<RecurringRule>,
    val primaryIncomeRule: RecurringRule?, // the main income that drives the cycle
    val daysUntilNextIncome: Int?,
)