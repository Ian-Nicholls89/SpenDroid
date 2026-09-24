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
        cardTiming: CardTiming = CardTiming.AT_BILL,
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

        /**
         * A joint account is a shared pot, and what it spends is not one person's budget.
         * The same shape as a credit card: the itemised spending is somebody else's problem
         * and the settlement - here, the standing order that funds it - is the real expense.
         *
         * Without this the arrangement was counted twice over and credited once: every penny
         * the pot spent came off this budget, while the money paid into it was written off
         * as a transfer between accounts.
         */
        val sharedAccountIds = accounts
            .filter { it.accountType == AccountType.JOINT }
            .map { it.id }
            .toSet()
        val atPurchase = cardTiming == CardTiming.AT_PURCHASE
        // Counted at the till, a card is spent from like any account, and paying its bill is
        // only moving money to cover what was already counted.
        val notSpendableFrom = if (atPurchase) sharedAccountIds else creditCardAccountIds + sharedAccountIds

        val cardAnalysis = CreditCardEngine.analyze(transactions, accounts, referenceTime.toLocalDate())

        // Whatever most accounts are denominated in; everything on screen is shown in it.
        val baseCurrency = accounts.groupingBy { it.currency }.eachCount()
            .maxByOrNull { it.value }?.key
            ?: transactions.firstOrNull()?.currency
            ?: "GBP"

        // Card transactions are excluded because the bill, not the itemised spend, is what
        // leaves the current account - the bill is added to upcomingFixed below. The card
        // payment itself is an internal transfer by shape, but it is the real cash outflow
        // under this model, so it is kept in.
        val relevantTransactions = transactions.filter { tx ->
            val isCardPayment = "${tx.accountId}|${tx.transactionId}" in cardAnalysis.cardPaymentKeys
            if (atPurchase && isCardPayment) return@filter false
            (!tx.isInternalTransfer || isCardPayment) &&
            tx.bookingDate.isNotBlank() &&
            tx.amountMinor != 0L &&
            !notSpendableFrom.contains(tx.accountId) &&
            // A foreign amount is not a sterling one. Adding 29.85 dollars to a pound total
            // as though the minor units were pence is simply wrong, and wrong quietly - so
            // it is left out and reported instead of being folded in.
            tx.currency == baseCurrency
        }

        // Pending included. The money is committed - the card has been presented and the
        // funds are held - so leaving it out understated spending until the bank got round
        // to booking it, which is exactly when knowing would have been useful.
        val booked = relevantTransactions

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
        val today = referenceTime.toLocalDate()

        val cardPayerRuleKeys = transactions
            .filter { "${it.accountId}|${it.transactionId}" in cardAnalysis.cardPaymentKeys }
            .mapTo(mutableSetOf()) { RecurringAnalyzer.groupKey(it) }

        val cashRules = rules.filter { rule ->
            // Moving your own money between your own accounts is neither earning nor
            // spending, however monthly it looks. Excluding the transactions was not enough:
            // the rule built from them still reached income and fixed outgoings, so a
            // standing order into a joint account was counted as a salary.
            if (rule.internalTransfer) return@filter false
            // History is kept long after the API's window has passed, so a job that ended
            // or a subscription that was cancelled is still detected from its old rows.
            // Counted, it is income that never arrives and a bill that is never taken.
            if (isStale(rule, today)) return@filter false
            // Paying a card by a fixed direct debit groups into a monthly rule of its own.
            // The card is already counted - by its bill or by its purchases - so this would
            // be the same money a second time.
            if (!rule.isManual && rule.key in cardPayerRuleKeys) return@filter false
            // A card's credits are bill payments and refunds, never income, whenever card
            // spending is counted.
            if (rule.direction == Direction.IN && rule.accountIds.isNotEmpty() &&
                rule.accountIds.all { it in creditCardAccountIds }
            ) {
                return@filter false
            }
            rule.accountIds.isEmpty() || !rule.accountIds.all { it in notSpendableFrom }
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
        // Discretionary spending, whenever it happened: what the cycle's figures count, before
        // they are cut to the cycle. The week's bars read from the same pool, so today's bar
        // and "spent today" cannot disagree.
        val discretionary = booked.mapNotNull { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@mapNotNull null
            val counted = tx.amountMinor < 0 &&
                RecurringAnalyzer.groupKey(tx) !in detectedFixedKeys &&
                manualFixedRules.none { RecurringAnalyzer.matches(it, tx) }
            if (counted) date to tx else null
        }
        val variableDebits = discretionary
            .filter { (date, _) -> date >= cycleStart && (cycleEnd == null || date <= cycleEnd) }
            .map { it.second }

        val byDay = discretionary.groupBy({ it.first }, { -it.second.amountMinor })
        val lastSevenDays = (6L downTo 0L).map { back -> byDay[today.minusDays(back)].orEmpty().sum() }

        val spentThisCycle = variableDebits.sumOf { -it.amountMinor }
        // Booking dates carry no time, so "the last 24 hours" could only ever mean today and
        // yesterday together - which is not what a figure labelled today says.
        val spentToday = variableDebits
            .filter { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate) == today }
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
            // computed instead: the statement while it is unpaid, since spending after the
            // close is next month's bill.
            val fromCards = cardAnalysis.bills.mapNotNull { bill ->
                val due = bill.dueDate ?: return@mapNotNull null
                if (bill.dueMinor <= 0L) return@mapNotNull null
                if (!due.isAfter(today) || due.isAfter(nextIncomeDate)) return@mapNotNull null
                UpcomingPayment(cardBillRule(bill, due), due, bill.dueMinor)
            }
            (fromRules + fromCards).sortedBy { it.dueDate }
        } else {
            emptyList()
        }

        val upcomingTotal = upcomingFixed.sumOf { it.amountMinor }

        /**
         * Card spending this cycle does not pay for: owed now but due after payday, plus where
         * the statement now building is heading. Counted at the bill it is out of sight until
         * then, so it is said out loud - the next cycle starts that much lighter.
         */
        val cardsAfterPayday = if (atPurchase) {
            0L
        } else {
            cardAnalysis.bills.sumOf { bill ->
                val thisCycle = upcomingFixed
                    .filter { it.rule.key == "$CARD_BILL_KEY_PREFIX${bill.cardAccountId}" }
                    .sumOf { it.amountMinor }
                val owedLater = (bill.outstandingMinor - thisCycle).coerceAtLeast(0L)
                val stillToCome = ((bill.projectedMinor ?: bill.unbilledMinor) - bill.unbilledMinor).coerceAtLeast(0L)
                owedLater + stillToCome
            }
        }

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
                        (RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { it >= cycleStart } == true)
                }
                .sumOf { it.amountMinor }
            (account.balanceMinor ?: return@let null) - sinceStart
        }

        /**
         * The share of the monthly budget this cycle has to last on. Paid weekly, the cycle is
         * a week, and handing the whole month's figure to seven days read as four times the
         * money there really was.
         */
        val cycleBudget = primaryIncome?.let { perCycle(variableBudget, it.cadence) } ?: variableBudget

        /**
         * Fixed commitments are already out of the budget - that is what makes it the variable
         * budget - so taking the ones still to come off again counted them twice, and straight
         * after payday, when all of them are still to come, the figure was short by every one.
         * Card bills are the exception: card spending is in neither the commitments nor the
         * spending above, so the bill is the only place it is counted at all.
         */
        val upcomingCardBills = if (atPurchase) {
            // Already counted, purchase by purchase.
            0L
        } else {
            upcomingFixed
                .filter { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) }
                .sumOf { it.amountMinor }
        }
        val freshStart = cycleBudget - spentThisCycle - upcomingCardBills

        /**
         * Carrying over is answered from the balance itself: what is in the account, less
         * what is already committed before the next income arrives.
         *
         * It used to add a rewound opening balance to the budget instead, which counted
         * money asymmetrically. Anything paid in mid-cycle that was not recurring income -
         * a refund, a transfer from someone, money moved off a card - is absent from the
         * budget, because the budget is built from detected income. But rewinding to the
         * start subtracted it, so the account looked to have begun the cycle that much
         * further down. The shortfall a payment implied was counted; the payment that
         * resolved it was not, and every windfall made the figure worse.
         */
        val rollover = potBalance?.let { it - upcomingTotal }

        val uncapped = when (budgetModel) {
            BudgetModel.FRESH_START, BudgetModel.SHOW_BOTH -> freshStart
            // No balance to work from means no carry to report; the budget is all there is.
            BudgetModel.ROLLOVER -> rollover ?: freshStart
        }
        val availableToSpend = uncapped.coerceAtLeast(0L)

        /**
         * The denominator the ring is drawn against, which has to be whatever the headline
         * figure is measured from or the two contradict each other - the ring read 96% used
         * beside a figure of nothing left, both correct and only one of them believable.
         */
        val spendableThisCycle = when (budgetModel) {
            BudgetModel.FRESH_START, BudgetModel.SHOW_BOTH -> cycleBudget
            BudgetModel.ROLLOVER -> spentThisCycle + availableToSpend
        }

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
            baseCurrency = baseCurrency,
            unconvertedCurrencies = transactions
                .asSequence()
                .filter { !it.isInternalTransfer && it.amountMinor != 0L }
                .filter { it.accountId !in creditCardAccountIds }
                .map { it.currency }
                .filter { it != baseCurrency }
                .toSet(),
            creditCardAccountIds = creditCardAccountIds,
            confirmedSettlementKeys = cardAnalysis.confirmedSettlementKeys,
            cardPayerKeys = cardAnalysis.cardPaymentKeys,
            budgetModel = budgetModel,
            potAccountId = potAccount?.id,
            openingBalanceMinor = openingBalance,
            spendableThisCycle = spendableThisCycle,
            // How far past nothing the figure really is, since zero cannot say.
            shortfallMinor = if (uncapped < 0L) -uncapped else 0L,
            potBalanceMinor = potBalance,
            cardTiming = cardTiming,
            cardsAfterPaydayMinor = cardsAfterPayday,
            lastSevenDaysMinor = lastSevenDays,
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
            amountMinor = -bill.dueMinor,
            currency = bill.currency,
            cadence = Cadence.MONTHLY,
            anchorDay = bill.nominalPaymentDay ?: due.dayOfMonth,
            lastOccurrence = due,
            occurrences = 1,
            score = 1f,
        )

    /** A monthly amount expressed as one cycle's worth, for a cycle of [cadence]. */
    private fun perCycle(monthly: Long, cadence: Cadence): Long = when (cadence) {
        Cadence.WEEKLY -> monthly * 12L / 52L
        Cadence.FORTNIGHTLY -> monthly * 12L / 26L
        Cadence.QUARTERLY -> monthly * 3L
        Cadence.ANNUAL -> monthly * 12L
        else -> monthly
    }

    /**
     * True once a detected rule has missed its slot by long enough that it has stopped:
     * half a period late plus a week, which clears weekends, bank holidays and a late
     * employer without keeping a cancelled direct debit alive for months. The user's own
     * rules are their statement of a commitment and never lapse on their own.
     */
    internal fun isStale(rule: RecurringRule, today: LocalDate): Boolean {
        if (rule.isManual) return false
        val periodDays = when (rule.cadence) {
            Cadence.WEEKLY -> 7L
            Cadence.FORTNIGHTLY -> 14L
            Cadence.QUARTERLY -> 92L
            Cadence.ANNUAL -> 365L
            else -> 31L
        }
        val allowance = periodDays + periodDays / 2 + 7L
        return ChronoUnit.DAYS.between(rule.lastOccurrence, today) > allowance
    }

    private fun monthlyEquivalent(rule: RecurringRule): Long = when (rule.cadence) {
        Cadence.WEEKLY -> rule.amountMinor * 52L / 12L
        Cadence.FORTNIGHTLY -> rule.amountMinor * 26L / 12L
        Cadence.QUARTERLY -> rule.amountMinor / 3L
        Cadence.ANNUAL -> rule.amountMinor / 12L
        else -> rule.amountMinor
    }
}