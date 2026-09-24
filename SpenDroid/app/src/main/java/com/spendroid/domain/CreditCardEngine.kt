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
 * So the bill is computed rather than detected, from three separate facts:
 *
 *  1. **How much is owed in total** comes from the bank's own balance. Summing transactions
 *     cannot answer this: the API only returns 90 days, so any balance carried from before
 *     that window is invisible, and a card that is not cleared in full carries one forever.
 *  2. **When the statement closes** is solved from history - see [solveStatementDay] - or
 *     set by the user, and then stored. It is a day of the month, so once known it stays
 *     known and the 90-day limit stops mattering.
 *  3. **The split** follows: what is charged after the close is not yet billed, and the rest
 *     of the balance is. That makes the total agree with the accounts screen by construction.
 *
 * The card only bills in arrears, which is the whole reason the split matters: spending
 * today is not money that leaves the current account this cycle, it is next cycle's bill.
 */
object CreditCardEngine {

    /** Payment terms vary, but the forecast only needs a plausible cycle length. */
    private const val MIN_PAYMENTS_TO_INFER = 2

    /** Where the statement close date came from, which decides whether to ask the user. */
    enum class CycleSource {
        /** The user told us. Always wins. */
        USER,

        /** Solved from past bills, and they reconciled. */
        INFERRED,

        /** Nothing fit, so the close was assumed a typical payment term before the due date. */
        ASSUMED,
    }

    /** Whether the total is the bank's figure or the weaker transaction-derived fallback. */
    enum class TotalSource { BANK_BALANCE, TRANSACTIONS }

    data class CardBill(
        val cardAccountId: String,
        val cardLabel: String,
        val currency: String,
        /** The whole liability: billed plus not-yet-billed. */
        val outstandingMinor: Long,
        /** The part already on a closed statement, so its amount is settled. */
        val billedMinor: Long,
        /** Charged since the statement closed; will land on the following bill. */
        val unbilledMinor: Long,
        /**
         * What the next payment will take, paid as the statement balance: the closed statement
         * while it is unpaid, and once it is settled, whatever is owed and still accruing.
         * Defaults to the whole balance where the statement is not known.
         */
        val dueMinor: Long = outstandingMinor,
        val dueDate: LocalDate?,
        /** Day of month the payment nominally lands on, before weekend drift. */
        val nominalPaymentDay: Int?,
        /** False when the due date was assumed rather than inferred from history. */
        val dueDateInferred: Boolean,
        /** Day of the month the statement closes, once known. */
        val statementDay: Int?,
        /** The most recent close on or before today. */
        val statementClose: LocalDate?,
        val cycleSource: CycleSource,
        val totalSource: TotalSource,
        /**
         * Average error per bill when the solved cycle was replayed against past statements.
         * Null when nothing could be checked. Small means the cycle is trustworthy.
         */
        val cycleFitErrorMinor: Long?,
        /** How many past bills the solved cycle was checked against. More is firmer. */
        val cycleBillsChecked: Int?,
        /** When the statement now building closes. */
        val nextStatementClose: LocalDate? = null,
        /** Days since the statement closed, which is how far into the new one today is. */
        val statementDaysElapsed: Int? = null,
        /** True once a payment has landed since the statement closed. */
        val statementPaid: Boolean = false,
        /**
         * Where the statement now building is heading: what is on it so far, plus the recent
         * daily rate carried to its close. Null until there is enough history to have a rate.
         */
        val projectedMinor: Long? = null,
        /** The middle of the bills actually paid, once there are two to go on. */
        val usualBillMinor: Long? = null,
        /** What spending on the card is measured against: the user's limit, else the usual bill. */
        val capMinor: Long? = null,
        val capSource: CapSource? = null,
    )

    /** Where a card's spending limit came from, which decides how it is worded. */
    enum class CapSource {
        /** The user set it. */
        USER,

        /** Nothing was set, so the card's usual bill stands in. */
        USUAL,
    }

    data class CardAnalysis(
        val bills: List<CardBill>,
        /**
         * "accountId|transactionId" of debits that pay a card. They are transfers between the
         * user's own accounts, but they are the real cash outflow under this model, so they
         * must stay counted as spending rather than being filtered out as internal transfers.
         */
        val cardPaymentKeys: Set<String>,
        /**
         * "accountId|transactionId" of the credits on the card that settle a bill. They are
         * not spending and not income - the cash already left on the payer's side - but they
         * are positive amounts, so without naming them they read as salary.
         */
        val cardSettlementKeys: Set<String>,
        /**
         * The subset of [cardSettlementKeys] whose other leg was actually found on a real
         * account. Only these are safe to hide: a settlement identified by guesswork might
         * be something else entirely, and hiding data is the costlier mistake.
         */
        val confirmedSettlementKeys: Set<String>,
    )

    fun analyze(
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>,
        today: LocalDate = LocalDate.now(),
    ): CardAnalysis {
        val cards = accounts.filter { it.accountType == AccountType.CREDIT_CARD }
        if (cards.isEmpty()) {
            return CardAnalysis(emptyList(), emptySet(), emptySet(), emptySet())
        }

        val byAccount = transactions.groupBy { it.accountId }
        val bills = mutableListOf<CardBill>()
        val paymentKeys = mutableSetOf<String>()
        val settlementKeys = mutableSetOf<String>()
        val confirmedSettlements = mutableSetOf<String>()

        for (card in cards) {
            val cardTxs = byAccount[card.id].orEmpty().filter { !it.isPending }
            if (cardTxs.isEmpty() && card.balanceMinor == null) continue

            // Credits on a card are payments or refunds, and telling them apart is the
            // whole job: a refund counted as a bill corrupts the cycle fit and reads as
            // money received. The reliable signal is the other leg - the debit that left a
            // real account - so every account that is not a card is searched, with the
            // linked one first so it wins a tie. Requiring the link meant an unlinked card
            // fell straight through to guessing.
            val linkedPayer = payerAccountFor(card, accounts)
            val payerTxs = (
                listOfNotNull(linkedPayer) +
                    accounts.filter {
                        it.accountType != AccountType.CREDIT_CARD && it.id != linkedPayer?.id
                    }
                )
                .flatMap { byAccount[it.id].orEmpty() }
                .filter { tx -> !tx.isPending }

            val payments = identifyPayments(cardTxs, payerTxs)
            payments.forEach { (cardTx, payerTx) ->
                val settlement = "${cardTx.accountId}|${cardTx.transactionId}"
                settlementKeys.add(settlement)
                if (payerTx != null) {
                    paymentKeys.add("${payerTx.accountId}|${payerTx.transactionId}")
                    confirmedSettlements.add(settlement)
                } else if (cardTx.isCardPayment) {
                    confirmedSettlements.add(settlement)
                }
            }
            val paymentIds = payments.mapTo(mutableSetOf()) { (cardTx, _) -> cardTx.transactionId }

            val paymentDates = payments.mapNotNull { (cardTx, _) ->
                RecurringAnalyzer.parseBookingDate(cardTx.bookingDate)
            }.sorted()

            val nominalDay = card.paymentDayOfMonth ?: inferPaymentDay(paymentDates)
            val due = nominalDay?.let { nextPaymentDate(it, today) }

            // The statement close is what separates a settled amount from one still moving,
            // so it is worth solving for properly rather than assuming a payment term.
            val fit = solveStatementDay(cardTxs, payments, today)
            val statementDay = card.statementDayOfMonth
                ?: fit?.day
                ?: due?.minusDays(TYPICAL_PAYMENT_TERM_DAYS)?.dayOfMonth
            val cycleSource = when {
                card.statementDayOfMonth != null -> CycleSource.USER
                fit != null -> CycleSource.INFERRED
                else -> CycleSource.ASSUMED
            }
            val statementClose = statementDay?.let { mostRecentOccurrence(it, today) }

            // A payment is not a charge, so it must not reduce what is still accruing -
            // clearing the statement leaves the post-close charges untouched.
            val unbilled = chargesSince(cardTxs, statementClose, paymentIds)

            val bankOwed = card.balanceMinor?.let { maxOf(0L, -it) }
            val outstanding = bankOwed ?: outstandingSince(cardTxs, paymentDates.lastOrNull())
            val billed = (outstanding - unbilled).coerceAtLeast(0L)

            // Spending after the close is next month's bill, so while this statement is still
            // to be paid, the statement is all that leaves. Once a payment has landed since the
            // close it is settled, and what is owed now is what the next bill will be built on.
            val statementPaid = statementClose != null &&
                paymentDates.any { it.isAfter(statementClose) && !it.isAfter(today) }
            val dueNext = when {
                statementClose == null -> outstanding
                statementPaid -> outstanding
                else -> billed
            }

            val nextClose = statementDay?.let { day -> statementClose?.let { onDay(it.plusMonths(1), day) } }
            val usual = medianOf(payments.map { (cardTx, _) -> cardTx.amountMinor }.filter { it > 0L })
            val projected = if (statementClose != null && nextClose != null) {
                projectStatement(unbilled, usual, today, statementClose, nextClose)
            } else {
                null
            }
            val cap = card.spendingCapMinor?.takeIf { it > 0L }

            bills += CardBill(
                cardAccountId = card.id,
                cardLabel = card.label,
                currency = card.currency,
                outstandingMinor = outstanding,
                billedMinor = billed,
                unbilledMinor = unbilled.coerceAtMost(outstanding),
                dueMinor = dueNext,
                dueDate = due,
                nominalPaymentDay = nominalDay,
                dueDateInferred = card.paymentDayOfMonth != null ||
                    paymentDates.size >= MIN_PAYMENTS_TO_INFER,
                statementDay = statementDay,
                statementClose = statementClose,
                cycleSource = cycleSource,
                totalSource = if (bankOwed != null) {
                    TotalSource.BANK_BALANCE
                } else {
                    TotalSource.TRANSACTIONS
                },
                cycleFitErrorMinor = fit?.averageErrorMinor,
                cycleBillsChecked = fit?.billsChecked,
                nextStatementClose = nextClose,
                statementPaid = statementPaid,
                statementDaysElapsed = statementClose?.let { (today.toEpochDay() - it.toEpochDay()).toInt() },
                projectedMinor = projected,
                usualBillMinor = usual,
                capMinor = cap ?: usual,
                capSource = when {
                    cap != null -> CapSource.USER
                    usual != null -> CapSource.USUAL
                    else -> null
                },
            )
        }

        return CardAnalysis(bills, paymentKeys, settlementKeys, confirmedSettlements)
    }

    /**
     * Where the statement now building is heading.
     *
     * The daily rate starts as the usual bill's and hands over to this statement's own as the
     * statement goes on, in proportion to how much of it has passed. It used to be the last
     * four weeks of card spending, which the day after a close is the statement just billed:
     * a card with nothing on it read as on pace to beat its usual bill, on the strength of
     * spending already paid for. Starting from the usual bill means a quiet start reads as
     * ordinary, and a busy one pulls the figure up as the days bear it out.
     *
     * With no usual bill yet there is nothing to start from, so the statement needs a week
     * of its own first.
     */
    internal fun projectStatement(
        soFar: Long,
        usualMinor: Long?,
        today: LocalDate,
        closedOn: LocalDate,
        closes: LocalDate,
    ): Long? {
        val cycleDays = (closes.toEpochDay() - closedOn.toEpochDay()).toDouble()
        if (cycleDays <= 0.0) return null
        val elapsed = (today.toEpochDay() - closedOn.toEpochDay()).toDouble().coerceIn(0.0, cycleDays)
        val daysLeft = cycleDays - elapsed

        val ownRate = if (elapsed > 0.0) soFar / elapsed else 0.0
        val rate = if (usualMinor != null) {
            val share = elapsed / cycleDays
            share * ownRate + (1 - share) * (usualMinor / cycleDays)
        } else {
            if (elapsed < MIN_OWN_PACE_DAYS) return null
            ownRate
        }
        return soFar + Math.round(rate * daysLeft)
    }

    /**
     * Whether a card's projection is worth a warning. Not in a statement's first week, when
     * the pace is mostly guesswork. Against the usual bill, only past a tenth over, since a few
     * pounds either side of usual is ordinary; a limit the user set is meant exactly.
     */
    fun projectedOverCap(bill: CardBill): Boolean {
        val cap = bill.capMinor?.takeIf { it > 0L } ?: return false
        val projected = bill.projectedMinor ?: return false
        if ((bill.statementDaysElapsed ?: 0) < MIN_OWN_PACE_DAYS) return false
        val line = if (bill.capSource == CapSource.USUAL) cap + cap / 10 else cap
        return projected > line
    }

    /**
     * The payments this card will take, as (date, amount), for as far ahead as is known.
     *
     * While the closed statement is unpaid, that is it on its due date, then the statement
     * building now a month later. Once it is paid, only the building one is left. Where the
     * cycle is unknown there is one payment of what is owed.
     */
    fun expectedPayments(bill: CardBill): List<Pair<LocalDate, Long>> {
        val due = bill.dueDate ?: return emptyList()
        val building = bill.projectedMinor ?: bill.unbilledMinor
        return when {
            bill.statementClose == null -> listOf(due to bill.outstandingMinor)
            bill.statementPaid -> listOf(due to maxOf(building, bill.dueMinor))
            else -> listOf(due to bill.billedMinor, due.plusMonths(1) to building)
        }.filter { it.second > 0L }
    }

    /** The middle value, averaging the two middles of an even count. Null under two values. */
    private fun medianOf(values: List<Long>): Long? {
        if (values.size < 2) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    /** A candidate statement day and how well it reproduced the bills actually paid. */
    data class StatementFit(
        val day: Int,
        val averageErrorMinor: Long,
        /** How many past bills the fit was checked against. More is more trustworthy. */
        val billsChecked: Int,
        /** How far the implied payment term sits from a typical one, used to break ties. */
        val termDeviationDays: Long,
    )

    /**
     * Works out which day of the month the statement closes, by replaying history.
     *
     * The insight is that a bill already paid tells us what a closed statement contained. So
     * for each candidate closing day, rebuild the statement it implies - every charge in the
     * month ending on that day - and compare it against what was actually paid. The day whose
     * rebuilt statements match the real bills is the real closing day.
     *
     * Scoring across every available bill rather than demanding one exact match is what makes
     * this survive real data: interest, fees and a refund posting a day late all knock an
     * exact sum off, but they do not move the best-fitting day.
     *
     * A window is only scored when the data covers all of it, since a truncated window always
     * looks too small, and a candidate is only scored when it puts the close a plausible
     * payment term before every bill.
     */
    internal fun solveStatementDay(
        cardTxs: List<TransactionEntity>,
        payments: List<Pair<TransactionEntity, TransactionEntity?>>,
        today: LocalDate,
    ): StatementFit? {
        if (payments.isEmpty()) return null

        val dated = cardTxs.mapNotNull { tx ->
            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { it to tx }
        }
        val earliest = dated.minOfOrNull { it.first } ?: return null
        val paymentIds = payments.mapTo(mutableSetOf()) { (cardTx, _) -> cardTx.transactionId }

        // Only genuine bill payments describe a statement; a refund does not.
        val observed = payments.mapNotNull { (cardTx, _) ->
            val date = RecurringAnalyzer.parseBookingDate(cardTx.bookingDate) ?: return@mapNotNull null
            if (cardTx.amountMinor <= 0L) return@mapNotNull null
            date to cardTx.amountMinor
        }
        if (observed.isEmpty()) return null

        var best: StatementFit? = null
        // Up to 31, not 28: a card can close on the last day of the month, and onDay clamps
        // a 31st to whatever the short month actually ends on, which is what such a card does.
        for (day in 1..31) {
            var totalError = 0L
            var totalTermDeviation = 0L
            var checked = 0
            var viable = true

            for ((paidOn, billMinor) in observed) {
                val close = mostRecentOccurrence(day, paidOn.minusDays(1))
                val term = paidOn.toEpochDay() - close.toEpochDay()
                if (term < MIN_TERM_DAYS || term > MAX_TERM_DAYS) {
                    viable = false
                    break
                }
                val priorClose = mostRecentOccurrence(day, close.minusDays(1))
                // A window that starts before the data does always rebuilds short.
                if (priorClose < earliest) continue

                val rebuilt = dated
                    .filter { (date, tx) ->
                        date > priorClose && date <= close && tx.transactionId !in paymentIds
                    }
                    .sumOf { (_, tx) -> tx.amountMinor }
                    .let { maxOf(0L, -it) }

                totalError += abs(rebuilt - billMinor)
                totalTermDeviation += abs(term - TYPICAL_PAYMENT_TERM_DAYS)
                checked++
            }

            if (!viable || checked == 0) continue
            val candidate = StatementFit(
                day = day,
                averageErrorMinor = totalError / checked,
                billsChecked = checked,
                termDeviationDays = totalTermDeviation / checked,
            )
            // Nothing charged on a given date makes closing that day indistinguishable from
            // closing the day before, so ties are real rather than a flaw in the search.
            // A typical payment term is the only other evidence available, so it decides.
            val better = best == null ||
                candidate.averageErrorMinor < best.averageErrorMinor ||
                (
                    candidate.averageErrorMinor == best.averageErrorMinor &&
                        candidate.termDeviationDays < best.termDeviationDays
                    )
            if (better) best = candidate
        }

        // A fit that is wrong by more than a rounding error is not a fit, and claiming one
        // would be worse than admitting the cycle is unknown.
        return best?.takeIf { it.averageErrorMinor <= MAX_FIT_ERROR_MINOR }
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

        // Anything the user has said is a bill payment counts as one, whether or not its
        // other leg is visible - which is the case this exists for: a card paid from an
        // account that was never linked has no other leg to find.
        //
        // The wording carries across months. A payment is named by the bank, not by a
        // merchant, so the same "DIRECT DEBIT PAYMENT" arrives every month; marking one
        // settles every other credit worded the same on that card, past and future.
        // Refunds cannot be caught by this: they carry the merchant's name, which is the
        // whole reason the two can be told apart at all.
        val declaredWordings = cardTxs
            .filter { it.isCardPayment && it.amountMinor > 0 }
            .mapTo(mutableSetOf()) { wording(it.payee) }
        val declared = credits
            .filter { credit ->
                wording(credit.payee) in declaredWordings &&
                    matched.none { (matchedCredit, _) -> matchedCredit === credit }
            }
            .map { it to null }

        // No guessing beyond that. The old rule took the largest credit each month, which
        // is a refund as often as a bill in a quiet month, and being wrong here moves the
        // statement cycle, the split and what shows in the list all at once.
        return matched + declared
    }

    /** A payee reduced to what is stable about it, for matching one month's wording to the next. */
    private fun wording(payee: String): String =
        payee.lowercase().trim().replace(Regex("\\s+"), " ")

    /**
     * What has been charged since [since], ignoring bill payments.
     *
     * Refunds do net off, because a refund genuinely reduces what the next bill will be; a
     * payment does not, because it settles the closed statement and leaves this window alone.
     */
    private fun chargesSince(
        cardTxs: List<TransactionEntity>,
        since: LocalDate?,
        paymentIds: Set<String>,
    ): Long {
        if (since == null) return 0L
        val net = cardTxs
            .filter { tx ->
                if (tx.transactionId in paymentIds) return@filter false
                val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
                date.isAfter(since)
            }
            .sumOf { it.amountMinor }
        return maxOf(0L, -net)
    }

    /**
     * Fallback total for a card whose balance the bank did not send: everything charged
     * since the last payment. Only correct when the card is cleared in full and the whole
     * period falls inside the 90-day window, which is why the balance is preferred.
     */
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

    /** The latest date on or before [onOrBefore] that falls on [day] of its month. */
    private fun mostRecentOccurrence(day: Int, onOrBefore: LocalDate): LocalDate {
        val thisMonth = onDay(onOrBefore, day)
        return if (thisMonth.isAfter(onOrBefore)) onDay(onOrBefore.minusMonths(1), day) else thisMonth
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

    /** Days a statement needs before its own pace means much. */
    private const val MIN_OWN_PACE_DAYS = 7

    /** UK cards typically fall due around three weeks after the statement closes. */
    private const val TYPICAL_PAYMENT_TERM_DAYS = 23L

    /** The plausible gap between a statement closing and its bill being taken. */
    private const val MIN_TERM_DAYS = 10L
    private const val MAX_TERM_DAYS = 35L

    /** Interest and a late-posting refund cost a few pounds; a wrong cycle costs far more. */
    private const val MAX_FIT_ERROR_MINOR = 500L
}
