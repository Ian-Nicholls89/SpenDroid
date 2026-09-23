package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.RuleOverrideEntity
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
        /** Key of the income the user chose to drive the cycle, if they chose one. */
        primaryIncomeKey: String? = null,
        budgetModel: BudgetModel = BudgetModel.FRESH_START,
        calendar: WorkingDayCalendar = WorkingDayCalendar(),
        overrides: Map<String, RuleOverrideEntity> = emptyMap(),
    ): BudgetSnapshot {
        fun nextFor(rule: RecurringRule, after: LocalDate): LocalDate {
            val override = overrides[rule.key]
            return RecurringAnalyzer.nextOccurrence(
                rule = rule,
                after = after,
                calendar = calendar,
                shift = PaymentShift.from(override?.shift) ?: PaymentShift.defaultFor(rule.direction),
                anchorDayOverride = override?.anchorDay,
                decemberAnchorDay = override?.decemberAnchorDay,
            )
        }
        val creditCardAccountIds = accounts
            .filter { it.accountType == AccountType.CREDIT_CARD }
            .map { it.id }
            .toSet()

        val cardAnalysis = CreditCardEngine.analyze(transactions, accounts, referenceTime.toLocalDate())

        // Card transactions are excluded because the bill, not the itemised spend, is what
        // leaves the current account - the bill is added to upcomingFixed below. The card
        // payment itself is an internal transfer by shape, but it is the real cash outflow
        // under this model, so it is kept in.
        val relevantTransactions = transactions.filter { tx ->
            val isCardPayment = "${tx.accountId}|${tx.transactionId}" in cardAnalysis.cardPaymentKeys
            !tx.isPending &&
            (!tx.isInternalTransfer || isCardPayment) &&
            tx.bookingDate.isNotBlank() &&
            tx.amountMinor != 0L &&
            !creditCardAccountIds.contains(tx.accountId)
        }

        val booked = relevantTransactions.filter { !it.isPending }

        // A rule detected entirely on a credit card is not cash moving in or out of the
        // current account, and counting it does real damage in both directions:
        //
        //  - Paying the bill posts a credit on the card. A bill paid at a steady amount
        //    groups into a recurring IN rule and is added to income, inflating the budget.
        //  - A subscription charged to the card groups into an OUT rule and is subtracted
        //    as a fixed outgoing - while the same spending is also inside the card bill,
        //    which is already deducted separately. The same money, taken twice.
        //
        // Manual rules carry no accounts and are always the user's own statement of a real
        // commitment, so they are kept.
        val cashRules = rules.filter { rule ->
            rule.accountIds.isEmpty() || !rule.accountIds.all { it in creditCardAccountIds }
        }
        val incomeRules = cashRules.filter { it.direction == Direction.IN }
        val fixedRules = cashRules.filter { it.direction == Direction.OUT }

        // The user's choice drives the cycle where they made one. Falling back to the
        // largest income keeps the app working when a designated rule stops being detected -
        // a new employer renames the payment and its key changes - and designationLost lets
        // the screen say so rather than the cycle silently moving.
        val designated = primaryIncomeKey?.let { key -> incomeRules.firstOrNull { it.key == key } }
        val designationLost = primaryIncomeKey != null && designated == null
        val primaryIncome = designated ?: incomeRules.maxByOrNull { monthlyEquivalent(it) }

        // Both are reported as positive magnitudes so the subtraction below is a subtraction:
        // OUT rules carry a negative amountMinor, and summing them signed would add the
        // outgoings back onto the budget instead.
        val averageMonthlyIncome = incomeRules.sumOf { abs(monthlyEquivalent(it)) }
        val fixedMonthlyOutgoings = fixedRules.sumOf { abs(monthlyEquivalent(it)) }
        val variableBudget = (averageMonthlyIncome - fixedMonthlyOutgoings).coerceAtLeast(0L)

        val today = referenceTime.toLocalDate()
        val referenceDate = referenceTime.toLocalDate()
        val startOfWindow = referenceTime.minusHours(24).toLocalDate()

        val nextIncomeDate = primaryIncome?.let { nextFor(it, today) }
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
            val fromRules = fixedRules.mapNotNull { rule ->
                val due = nextFor(rule, today)
                if (due.isAfter(today) && !due.isAfter(nextIncomeDate)) {
                    UpcomingPayment(rule, due, abs(rule.amountMinor))
                } else {
                    null
                }
            }
            // Card bills are variable, so RecurringAnalyzer cannot detect them; they are
            // computed from the outstanding balance instead.
            val fromCards = cardAnalysis.bills.mapNotNull { bill ->
                val due = bill.dueDate ?: return@mapNotNull null
                if (bill.outstandingMinor <= 0L) return@mapNotNull null
                if (!due.isAfter(today) || due.isAfter(nextIncomeDate)) return@mapNotNull null
                UpcomingPayment(cardBillRule(bill, due), due, bill.outstandingMinor)
            }
            (fromRules + fromCards).sortedBy { it.dueDate }
        } else {
            emptyList()
        }

        val upcomingTotal = upcomingFixed.sumOf { it.amountMinor }

        // The pot is the account the main income lands in, which makes designating the income
        // designate the account too, with no second setting to keep in step.
        val potAccount = primaryIncome?.let { income ->
            val paidInto = transactions
                .filter { it.amountMinor > 0 && RecurringAnalyzer.matches(income, it) }
                .groupingBy { it.accountId }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            accounts.firstOrNull { it.id == paidInto }
        }
        val potBalance = potAccount?.balanceMinor

        // Rewind today's balance over everything booked since the cycle began to get what was
        // there at the start. The balance is only as fresh as the last sync, so this is an
        // estimate, and it is the honest one available without storing daily snapshots.
        val openingBalance = potAccount?.let { account ->
            val sinceStart = transactions
                .filter { tx ->
                    tx.accountId == account.id &&
                        !tx.isPending &&
                        (RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { it >= cycleStart } == true)
                }
                .sumOf { it.amountMinor }
            (account.balanceMinor ?: return@let null) - sinceStart
        }

        val freshStart = variableBudget - spentThisCycle - upcomingTotal
        val availableToSpend = when (budgetModel) {
            BudgetModel.FRESH_START, BudgetModel.SHOW_BOTH -> freshStart
            // What was already there is spendable too, so it joins this cycle's budget.
            BudgetModel.ROLLOVER -> freshStart + (openingBalance ?: 0L)
        }.coerceAtLeast(0L)

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
            cardBills = cardAnalysis.bills,
            // Both sides, so the credit on the card reads as a bill settlement rather
            // than as salary. The filter above deliberately uses the payer side only.
            cardPaymentKeys = cardAnalysis.cardPaymentKeys + cardAnalysis.cardSettlementKeys,
            creditCardAccountIds = creditCardAccountIds,
            budgetModel = budgetModel,
            potAccountId = potAccount?.id,
            openingBalanceMinor = openingBalance,
            potBalanceMinor = potBalance,
            primaryIncomeDesignated = designated != null,
            designationLost = designationLost,
        )
    }

    /**
     * A card bill presented as a rule so it sits alongside detected deductions in the UI.
     * Deliberately not added to [BudgetSnapshot.fixedRules]: the amount varies month to
     * month, so it must not become part of the fixed baseline that sets the variable budget.
     */
    private fun cardBillRule(bill: CreditCardEngine.CardBill, due: LocalDate): RecurringRule =
        RecurringRule(
            key = "$CARD_BILL_KEY_PREFIX${bill.cardAccountId}",
            payee = bill.cardLabel,
            direction = Direction.OUT,
            amountMinor = -bill.outstandingMinor,
            currency = bill.currency,
            cadence = Cadence.MONTHLY,
            anchorDay = bill.nominalPaymentDay ?: due.dayOfMonth,
            lastOccurrence = due,
            occurrences = 1,
            score = 1f,
        )

    private fun monthlyEquivalent(rule: RecurringRule): Long = when (rule.cadence) {
        Cadence.WEEKLY -> rule.amountMinor * 52L / 12L
        Cadence.FORTNIGHTLY -> rule.amountMinor * 26L / 12L
        Cadence.QUARTERLY -> rule.amountMinor / 3L
        Cadence.ANNUAL -> rule.amountMinor / 12L
        else -> rule.amountMinor
    }
}