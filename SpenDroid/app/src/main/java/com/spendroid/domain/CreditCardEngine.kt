package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * Anticipates credit card bills.
 *
 * Card spending is a cash-flow problem: the itemised transactions on the card are not money
 * leaving the current account, the monthly bill is. But the bill is variable, so
 * [RecurringAnalyzer] cannot see it - groupKey buckets by amount, and a bill of £412 then
 * £380 lands in different buckets and never groups.
 *
 * So the bill is computed rather than detected. The rule that keeps it from double-counting:
 * **card transactions are used to compute a liability, they are never spending events.** The
 * bill payment is the only cash movement, and it settles a liability already accounted for.
 *
 * Assumes the card is paid off in full each month, which makes the outstanding liability
 * simply everything charged since the last payment.
 */
object CreditCardEngine {

    /** Payment terms vary, but the forecast only needs a plausible cycle length. */
    private const val MIN_PAYMENTS_TO_INFER = 2

    data class CardBill(
        val cardAccountId: String,
        val cardLabel: String,
        val currency: String,
        /** Everything charged since the last payment: billed plus not-yet-billed. */
        val outstandingMinor: Long,
        /** The part already on a closed statement, so its amount is settled. */
        val billedMinor: Long,
        /** Charged since the statement closed; will land on the following bill. */
        val unbilledMinor: Long,
        val dueDate: LocalDate?,
        /** Day of month the payment nominally lands on, before weekend drift. */
        val nominalPaymentDay: Int?,
        /** False when the due date was assumed rather than inferred from history. */
        val dueDateInferred: Boolean,
    )

    data class CardAnalysis(
        val bills: List<CardBill>,
        /**
         * "accountId|transactionId" of debits that pay a card. They are transfers between the
         * user's own accounts, but they are the real cash outflow under this model, so they
         * must stay counted as spending rather than being filtered out as internal transfers.
         */
        val cardPaymentKeys: Set<String>,
    )

    fun analyze(
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>,
        today: LocalDate = LocalDate.now(),
    ): CardAnalysis {
        val cards = accounts.filter { it.accountType == AccountType.CREDIT_CARD }
        if (cards.isEmpty()) return CardAnalysis(emptyList(), emptySet())

        val byAccount = transactions.groupBy { it.accountId }
        val bills = mutableListOf<CardBill>()
        val paymentKeys = mutableSetOf<String>()

        for (card in cards) {
            val cardTxs = byAccount[card.id].orEmpty().filter { !it.isPending }
            if (cardTxs.isEmpty()) continue

            // Credits on a card are payments or refunds. Pairing against the paying account
            // tells them apart; without a link, fall back to the largest credit per month,
            // since a paid-in-full bill dwarfs a typical refund.
            val payerTxs = payerAccountFor(card, accounts)
                ?.let { byAccount[it.id].orEmpty().filter { tx -> !tx.isPending } }
                .orEmpty()

            val payments = identifyPayments(cardTxs, payerTxs)
            payments.forEach { (_, payerTx) ->
                payerTx?.let { paymentKeys.add("${it.accountId}|${it.transactionId}") }
            }

            val paymentDates = payments.mapNotNull { (cardTx, _) ->
                RecurringAnalyzer.parseBookingDate(cardTx.bookingDate)
            }.sorted()

            val lastPayment = paymentDates.lastOrNull()
            val outstanding = outstandingSince(cardTxs, lastPayment)

            val nominalDay = inferPaymentDay(paymentDates)
            val due = nominalDay?.let { nextPaymentDate(it, today) }

            // The statement closed roughly a payment term before the bill is taken. Splitting
            // there separates a settled amount from one still moving, which is the difference
            // between "this is your bill" and "this is what it is heading towards".
            val statementClose = due?.minusDays(TYPICAL_PAYMENT_TERM_DAYS)
                ?.takeIf { !it.isAfter(today) }
            val unbilled = if (statementClose == null) {
                0L
            } else {
                outstandingSince(cardTxs, statementClose)
            }

            bills += CardBill(
                cardAccountId = card.id,
                cardLabel = card.label,
                currency = card.currency,
                outstandingMinor = outstanding,
                billedMinor = (outstanding - unbilled).coerceAtLeast(0L),
                unbilledMinor = unbilled.coerceAtMost(outstanding),
                dueDate = due,
                nominalPaymentDay = nominalDay,
                dueDateInferred = paymentDates.size >= MIN_PAYMENTS_TO_INFER,
            )
        }

        return CardAnalysis(bills, paymentKeys)
    }

    /**
     * The account that pays [card]. The stored link holds the counterpart account and may
     * have been set from either side, so both directions are checked.
     */
    private fun payerAccountFor(card: AccountEntity, accounts: List<AccountEntity>): AccountEntity? {
        val fromCard = card.linkedCreditCardAccountId
            ?.let { id -> accounts.firstOrNull { it.id == id } }
            ?.takeIf { it.accountType != AccountType.CREDIT_CARD }
        if (fromCard != null) return fromCard
        return accounts.firstOrNull {
            it.accountType != AccountType.CREDIT_CARD && it.linkedCreditCardAccountId == card.id
        }
    }

    /**
     * Card credits that are bill payments, paired with the matching debit on the paying
     * account where one can be found.
     *
     * Matching is deliberately loose on dates - the two sides post on different days - but
     * exact on amount, so a refund that happens to coincide is not mistaken for a payment.
     */
    private fun identifyPayments(
        cardTxs: List<TransactionEntity>,
        payerTxs: List<TransactionEntity>,
    ): List<Pair<TransactionEntity, TransactionEntity?>> {
        val credits = cardTxs.filter { it.amountMinor > 0 }
        if (credits.isEmpty()) return emptyList()

        val usedPayer = mutableSetOf<String>()
        val matched = mutableListOf<Pair<TransactionEntity, TransactionEntity?>>()

        for (credit in credits) {
            val creditDate = RecurringAnalyzer.parseBookingDate(credit.bookingDate) ?: continue
            val payer = payerTxs.firstOrNull { candidate ->
                val key = "${candidate.accountId}|${candidate.transactionId}"
                if (key in usedPayer) return@firstOrNull false
                if (candidate.amountMinor != -credit.amountMinor) return@firstOrNull false
                val date = RecurringAnalyzer.parseBookingDate(candidate.bookingDate)
                    ?: return@firstOrNull false
                abs(date.toEpochDay() - creditDate.toEpochDay()) <= PAYMENT_MATCH_DAYS
            }
            if (payer != null) {
                usedPayer.add("${payer.accountId}|${payer.transactionId}")
                matched += credit to payer
            }
        }

        // With a paying account linked, matched credits are the payments. Without one, treat
        // the largest credit in each month as the bill payment.
        if (matched.isNotEmpty()) return matched
        return credits
            .groupBy { it.bookingDate.take(7) }
            .values
            .mapNotNull { monthCredits -> monthCredits.maxByOrNull { it.amountMinor } }
            .map { it to null }
    }

    /** Everything charged since [lastPayment], which under pay-in-full is the whole balance. */
    private fun outstandingSince(
        cardTxs: List<TransactionEntity>,
        lastPayment: LocalDate?,
    ): Long {
        val relevant = cardTxs.filter { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
            lastPayment == null || date.isAfter(lastPayment)
        }
        // Debits are negative, credits positive; the outstanding balance is what is owed.
        val net = relevant.sumOf { it.amountMinor }
        return if (net < 0) -net else 0L
    }

    /**
     * The day of the month payments nominally fall on.
     *
     * A direct debit due on a weekend is taken on the next working day, so observed dates
     * drift forwards and never backwards. The most common day is taken, breaking ties
     * towards the earliest, since drift can only have pushed dates later.
     */
    private fun inferPaymentDay(paymentDates: List<LocalDate>): Int? {
        if (paymentDates.isEmpty()) return null
        val counts = paymentDates.groupingBy { it.dayOfMonth }.eachCount()
        val highest = counts.values.max()
        return counts.filterValues { it == highest }.keys.min()
    }

    /**
     * Next occurrence of [nominalDay] after [today], rolled forward off weekends.
     *
     * Bank holidays shift payments too but cannot be derived without a calendar, so a
     * forecast can be a day or two early around them. That only matters if it moves the
     * payment across a payday boundary, which is why the date is reported as inferred.
     */
    private fun nextPaymentDate(nominalDay: Int, today: LocalDate): LocalDate {
        var candidate = onDay(today, nominalDay)
        if (!candidate.isAfter(today)) {
            candidate = onDay(today.plusMonths(1), nominalDay)
        }
        return rollForwardOffWeekend(candidate)
    }

    private fun onDay(month: LocalDate, day: Int): LocalDate =
        month.withDayOfMonth(day.coerceAtMost(month.lengthOfMonth()))

    private fun rollForwardOffWeekend(date: LocalDate): LocalDate {
        var result = date
        while (result.dayOfWeek == DayOfWeek.SATURDAY || result.dayOfWeek == DayOfWeek.SUNDAY) {
            result = result.plusDays(1)
        }
        return result
    }

    private const val PAYMENT_MATCH_DAYS = 5L

    /** UK cards typically fall due around three weeks after the statement closes. */
    private const val TYPICAL_PAYMENT_TERM_DAYS = 23L
}
