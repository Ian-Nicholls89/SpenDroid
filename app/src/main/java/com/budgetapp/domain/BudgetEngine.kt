package com.budgetapp.domain

import com.budgetapp.data.db.AccountEntity
import com.budgetapp.data.db.AccountType
import com.budgetapp.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object BudgetEngine {

    fun snapshot(
        transactions: List<TransactionEntity>,
        rules: List<RecurringRule>,
        accounts: List<AccountEntity> = emptyList(),
    ): BudgetSnapshot {
        // Map accountId -> AccountType for filtering
        val accountTypeMap = accounts.associateBy({ it.id }, { it.accountType })
        val creditCardAccountIds = accounts
            .filter { it.accountType == AccountType.CREDIT_CARD }
            .map { it.id }
            .toSet()
        
        // Personal accounts that pay credit cards
        val personalAccountIds = accounts
            .filter { it.accountType == AccountType.PERSONAL }
            .map { it.id }
            .toSet()

        // Filter out credit card transactions (they're paid from personal account)
        // Also filter out internal transfers
        val relevantTransactions = transactions.filter { tx ->
            !tx.isPending &&
            !tx.isInternalTransfer &&
            tx.bookingDate.isNotBlank() &&
            tx.amountMinor != 0L &&
            !creditCardAccountIds.contains(tx.accountId)
        }

        val booked = relevantTransactions.filter { !it.isPending }
        val incomeRules = rules.filter { it.direction == Direction.IN }
        val fixedRules = rules.filter { it.direction == Direction.OUT }

        // Identify primary income (largest regular income)
        val primaryIncome = incomeRules.maxByOrNull { monthlyEquivalent(it) }

        val averageMonthlyIncome = incomeRules.sumOf { monthlyEquivalent(it) }
        val fixedMonthlyOutgoings = fixedRules.sumOf { monthlyEquivalent(it) }
        val variableBudget = (averageMonthlyIncome - fixedMonthlyOutgoings).coerceAtLeast(0L)

        val today = LocalDate.now()
        val nextIncomeDate = primaryIncome?.let { RecurringAnalyzer.nextOccurrence(it, today) }
        val lastIncome = primaryIncome?.lastOccurrence?.takeIf { !it.isAfter(today) }
        val cycleStart = lastIncome?.takeIf { it.isAfter(today.minusDays(45)) } ?: today.minusDays(30)
        val cycleEnd = nextIncomeDate?.minusDays(1)
        
        val daysUntilNextIncome = nextIncomeDate?.let { ChronoUnit.DAYS.between(today, it).toInt() }

        val fixedKeys = fixedRules.map { it.key }.toSet()
        val variableDebits = booked.filter { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
            tx.amountMinor < 0 &&
                date >= cycleStart &&
                (cycleEnd == null || date <= cycleEnd) &&
                RecurringAnalyzer.groupKey(tx) !in fixedKeys
        }

        val spentThisCycle = variableDebits.sumOf { -it.amountMinor }
        val spentToday = variableDebits
            .filter { it.bookingDate == today.toString() }
            .sumOf { -it.amountMinor }

        val upcomingFixed = if (nextIncomeDate != null) {
            fixedRules
                .mapNotNull { rule ->
                    val due = RecurringAnalyzer.nextOccurrence(rule, today)
                    if (due.isAfter(today) && !due.isAfter(nextIncomeDate)) {
                        UpcomingPayment(rule, due, rule.amountMinor)
                    } else {
                        null
                    }
                }
                .sortedBy { it.dueDate }
        } else {
            emptyList()
        }

        val upcomingTotal = upcomingFixed.sumOf { it.amountMinor }
        val availableToSpend = (variableBudget - spentThisCycle - upcomingTotal).coerceAtLeast(0L)

        return BudgetSnapshot(
            averageMonthlyIncome = averageMonthlyIncome,
            fixedMonthlyOutgoings = fixedMonthlyOutgoings,
            variableMonthlyBudget = variableBudget,
            nextIncomeDate = nextIncomeDate,
            cycleStart = cycleStart,
            cycleEnd = cycleEnd,
            spentThisCycle = spentThisCycle,
            spentToday = spentToday,
            upcomingFixed = upcomingFixed,
            availableToSpend = availableToSpend,
            incomeRules = incomeRules,
            fixedRules = fixedRules,
            primaryIncomeRule = primaryIncome,
            daysUntilNextIncome = daysUntilNextIncome,
        )
    }

    private fun monthlyEquivalent(rule: RecurringRule): Long = when (rule.cadence) {
        Cadence.WEEKLY -> rule.amountMinor * 52L / 12L
        Cadence.FORTNIGHTLY -> rule.amountMinor * 26L / 12L
        Cadence.QUARTERLY -> rule.amountMinor / 3L
        Cadence.ANNUAL -> rule.amountMinor / 12L
        else -> rule.amountMinor
    }
}