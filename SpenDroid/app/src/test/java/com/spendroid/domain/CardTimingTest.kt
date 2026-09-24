package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card spending counts once, either at the till or when the bill is paid, never both and
 * never neither. These pin down both timings against the same household.
 */
class CardTimingTest {

    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    private val accounts = listOf(
        AccountEntity("current", "NatWest", "Current", "GBP", 180000, 0L, AccountType.PERSONAL),
        AccountEntity("card", "Tesco Bank", "Tesco Card", "GBP", -4000, 0L, AccountType.CREDIT_CARD),
    )

    private var seq = 0

    private fun tx(account: String, date: String, amountMinor: Long, payee: String) = TransactionEntity(
        accountId = account,
        transactionId = "tx-${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
    )

    private val transactions = buildList {
        listOf("2026-06-28", "2026-07-28", "2026-08-28").forEach {
            add(tx("current", it, 250000, "ACME LTD SALARY"))
        }
        // A card subscription, which is a real commitment once card spending counts.
        listOf("2026-07-03", "2026-08-03", "2026-09-03").forEach {
            add(tx("card", it, -1099, "NETFLIX"))
        }
        // Paying last month's statement, both legs visible.
        add(tx("current", "2026-09-05", -6200, "TESCO BANK"))
        add(tx("card", "2026-09-05", 6200, "PAYMENT RECEIVED"))
        // This month's shopping on the card, and on the account.
        add(tx("card", "2026-09-12", -3000, "SAINSBURYS"))
        add(tx("current", "2026-09-14", -2000, "PRET"))
    }

    private fun snapshot(timing: CardTiming, model: BudgetModel = BudgetModel.FRESH_START) =
        BudgetEngine.snapshot(
            transactions = transactions,
            rules = RecurringAnalyzer.analyze(transactions),
            accounts = accounts,
            referenceTime = now,
            budgetModel = model,
            cardTiming = timing,
        )

    @Test
    fun `at the bill, the payment is the spending and the purchases are not`() {
        val s = snapshot(CardTiming.AT_BILL)
        assertEquals(6200L + 2000L, s.spentThisCycle)
        assertEquals(0L, s.fixedMonthlyOutgoings)
    }

    @Test
    fun `when you spend, the purchases are the spending and the payment is not`() {
        val s = snapshot(CardTiming.AT_PURCHASE)
        assertEquals(3000L + 2000L, s.spentThisCycle)
        // The card subscription is now a commitment like any direct debit.
        assertEquals(1099L, s.fixedMonthlyOutgoings)
    }

    @Test
    fun `when you spend, the bill is not taken off again`() {
        // Payday on the 8th, so the card's next bill on the 5th falls inside the cycle.
        val late = transactions.map {
            if (it.payee == "ACME LTD SALARY") it.copy(bookingDate = it.bookingDate.replace("-28", "-08")) else it
        }
        fun at(timing: CardTiming) = BudgetEngine.snapshot(
            transactions = late,
            rules = RecurringAnalyzer.analyze(late),
            accounts = accounts,
            referenceTime = now,
            cardTiming = timing,
        )
        val purchase = at(CardTiming.AT_PURCHASE)
        val bill = at(CardTiming.AT_BILL)
        val cardBill = purchase.upcomingFixed.single { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) }

        // Still listed, because it still leaves the account...
        assertTrue(cardBill.amountMinor > 0L)
        // ...but only the bill timing takes it off the budget.
        assertEquals(250000L - 1099L - 3000L - 2000L, purchase.availableToSpend)
        assertEquals(250000L - bill.spentThisCycle - cardBill.amountMinor, bill.availableToSpend)
    }

    /** The balance still has to pay the bill whichever way it was counted. */
    @Test
    fun `carrying over still holds the bill back from the balance`() {
        val atBill = snapshot(CardTiming.AT_BILL, BudgetModel.ROLLOVER)
        val atPurchase = snapshot(CardTiming.AT_PURCHASE, BudgetModel.ROLLOVER)
        assertEquals(atBill.availableToSpend, atPurchase.availableToSpend)
    }

    /**
     * A card paid by a fixed direct debit groups into a monthly rule on the paying account.
     * Counted as a commitment, that is the card bill a second time.
     */
    @Test
    fun `a steady card payment is not a commitment of its own`() {
        val steady = transactions.filterNot { it.payee == "TESCO BANK" || it.payee == "PAYMENT RECEIVED" } +
            listOf("2026-07-05", "2026-08-05", "2026-09-05").flatMap {
                listOf(tx("current", it, -5000, "TESCO BANK"), tx("card", it, 5000, "PAYMENT RECEIVED"))
            }
        val s = BudgetEngine.snapshot(
            transactions = steady,
            rules = RecurringAnalyzer.analyze(steady),
            accounts = accounts,
            referenceTime = now,
        )
        assertTrue(s.fixedRules.none { it.payee == "TESCO BANK" })
    }
}
