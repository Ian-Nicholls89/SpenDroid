package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object BudgetEngine {

    fun snapshot(
        transactions: List<TransactionEntity>,
        rules: List<RecurringRule>,
        accounts: List<AccountEntity> = emptyList(),
        referenceTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
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

        // Both are reported as positive magnitudes so the subtraction below is a subtraction:
        // OUT rules carry a negative amountMinor, and summing them signed would add the
        // outgoings back onto the budget instead.
        val averageMonthlyIncome = incomeRules.sumOf { abs(monthlyEquivalent(it)) }
        val fixedMonthlyOutgoings = fixedRules.sumOf { abs(monthlyEquivalent(it)) }
        val variableBudget = (averageMonthlyIncome - fixedMonthlyOutgoings).coerceAtLeast(0L)

        val today = referenceTime.toLocalDate()
        val referenceDate = referenceTime.toLocalDate()
        val startOfWindow = referenceTime.minusHours(24).toLocalDate()

        val nextIncomeDate = primaryIncome?.let { RecurringAnalyzer.nextOccurrence(it, today) }
        val lastIncome = primaryIncome?.lastOccurrence?.takeIf { !it.isAfter(today) }
        val cycleStart = lastIncome?.takeIf { it.isAfter(today.minusDays(45)) } ?: today.minusDays(30)
        val cycleEnd = nextIncomeDate?.minusDays(1)

        val daysUntilNextIncome = nextIncomeDate?.let { ChronoUnit.DAYS.between(today, it).toInt() }

        // Detected rules are keyed by groupKey, so they exclude their transactions with a set
        // lookup. Manual rules never produce a matching key and need the fuzzy matcher - but
        // there are only a handful of them, so the inner scan stays cheap.
        val detectedFixedKeys = fixedRules.filterNot { it.isManual }.map { it.key }.toSet()
        val manualFixedRules = fixedRules.filter { it.isManual }
        val variableDebits = booked.filter { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
            tx.amountMinor < 0 &&
                date >= cycleStart &&
                (cycleEnd == null || date <= cycleEnd) &&
                RecurringAnalyzer.groupKey(tx) !in detectedFixedKeys &&
                manualFixedRules.none { RecurringAnalyzer.matches(it, tx) }
        }

        val spentThisCycle = variableDebits.sumOf { -it.amountMinor }
        val spentToday = variableDebits
            .filter { tx ->
                val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
                date >= startOfWindow && date <= referenceDate
            }
            .sumOf { -it.amountMinor }

        val upcomingFixed = if (nextIncomeDate != null) {
            fixedRules
                .mapNotNull { rule ->
                    val due = RecurringAnalyzer.nextOccurrence(rule, today)
                    if (due.isAfter(today) && !due.isAfter(nextIncomeDate)) {
                        UpcomingPayment(rule, due, abs(rule.amountMinor))
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