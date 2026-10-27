package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate

/**
 * The month's money, cards and all (0.7.1): what's left after the bills - Home's figure - plus
 * the card bills this pay cycle pays. Those bills are last month's card spending, so this is what
 * the month has to spend from the account and on the cards together, if this month's card
 * spending is to come to what last month's did.
 *
 * As the user put it, 26 Oct 2026: £163.40 left + £593.66 of card statements (£539.20 and
 * £54.46) = £757.06 for the month. Next to it, what's gone on the cards since payday - next
 * month's bills in the making - against those statements.
 */
object MonthlyAvailable {

    /** One card's bill this cycle: [paid] already, or still to come. */
    data class Bill(val label: String, val amountMinor: Long, val paid: Boolean, val due: LocalDate?)

    data class Month(
        /** Home's figure: left to spend before payday, after every bill. */
        val leftMinor: Long,
        /** How far over the account's spending is, below Home's £0.00: it comes off the month too. */
        val overMinor: Long,
        val bills: List<Bill>,
        /** On the cards since payday, pending included: what next cycle's bills will be made of. */
        val spentOnCardsMinor: Long,
        val until: LocalDate?,
    ) {
        val cardBillsMinor: Long get() = bills.sumOf { it.amountMinor }
        val totalMinor: Long get() = leftMinor - overMinor + cardBillsMinor
    }

    /** The month, or null with no card bill this cycle - then it'd only be Home's figure again. */
    fun of(budget: BudgetSnapshot, transactions: List<TransactionEntity>, accounts: List<AccountEntity>): Month? {
        val from = budget.cycleStart
        val today = budget.asOf
        val payday = budget.nextIncomeDate
        fun inCycle(tx: TransactionEntity): Boolean {
            val d = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return false
            return !d.isBefore(from) && !d.isAfter(today)
        }
        fun labelOf(cardId: String) = (budget.cardBills.firstOrNull { it.cardAccountId == cardId }?.cardLabel
            ?: accounts.firstOrNull { it.id == cardId }?.label ?: "Card").trim()

        // Still to come before payday.
        val toCome = budget.upcomingFixed
            .filter { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) && it.amountMinor > 0L && (payday == null || it.dueDate.isBefore(payday)) }
            .map { Bill(labelOf(it.rule.key.removePrefix(CARD_BILL_KEY_PREFIX)), it.amountMinor, paid = false, due = it.dueDate) }
        // Paid since payday, from the account the pay goes into.
        val paid = transactions
            .filter { tx ->
                tx.amountMinor < 0L && "${tx.accountId}|${tx.transactionId}" in budget.cardPayerKeys && inCycle(tx) &&
                    (budget.potAccountId == null || tx.accountId == budget.potAccountId)
            }
            .map { tx ->
                // Which card it paid: the one the same amount reached within a few days, else the
                // payee as the bank says.
                val on = RecurringAnalyzer.parseBookingDate(tx.bookingDate)
                val card = transactions.firstOrNull { c ->
                    c.accountId in budget.creditCardAccountIds && c.amountMinor == -tx.amountMinor && on != null &&
                        RecurringAnalyzer.parseBookingDate(c.bookingDate)?.let { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(on, it)) <= 5 } == true
                }?.accountId
                Bill(card?.let(::labelOf) ?: ShopName.clean(tx.payee) ?: "Card bill", -tx.amountMinor, paid = true, due = RecurringAnalyzer.parseBookingDate(tx.bookingDate))
            }
        val bills = (paid + toCome).sortedBy { it.due }
        if (bills.isEmpty()) return null

        val spentOnCards = transactions
            .filter { tx ->
                tx.accountId in budget.creditCardAccountIds && tx.amountMinor < 0L && inCycle(tx) && !tx.isInternalTransfer &&
                    "${tx.accountId}|${tx.transactionId}" !in budget.cardPaymentKeys
            }
            .sumOf { -it.amountMinor }
        return Month(budget.availableToSpend, budget.shortfallMinor, bills, spentOnCards, payday)
    }

    /** Penny's line on it: the cards since payday, against last month's bills. Never over 70 characters. */
    fun line(month: Month, money: (Long) -> String): String {
        val bills = month.cardBillsMinor
        val spent = month.spentOnCardsMinor
        return when {
            spent == 0L -> "Nothing on the cards since payday. Last month's bills: ${money(bills)}."
            spent <= bills -> "${money(spent)} on cards since payday, of last month's ${money(bills)}."
            else -> "${money(spent - bills)} more on cards than last month's bills came to."
        }
    }
}
