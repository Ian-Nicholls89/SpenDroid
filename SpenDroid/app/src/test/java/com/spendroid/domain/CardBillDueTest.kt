package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A direct debit for the statement balance takes what was on the statement, not everything
 * owed today. Spending since the statement closed lands on the next bill, and holding it
 * back from this cycle as well made the budget short by exactly that much.
 */
class CardBillDueTest {

    private var seq = 0

    private fun tx(
        date: String,
        amountMinor: Long,
        account: String = "card",
        payee: String = if (amountMinor > 0) "PAYMENT RECEIVED" else "SHOP",
        declaredPayment: Boolean = false,
    ) = TransactionEntity(
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
        isCardPayment = declaredPayment,
    )

    private fun card(balanceMinor: Long) = AccountEntity(
        id = "card",
        institutionName = "Tesco Bank",
        label = "Tesco Credit Card",
        currency = "GBP",
        balanceMinor = balanceMinor,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
    )

    private val current = AccountEntity(
        id = "current",
        institutionName = "NatWest",
        label = "Current",
        currency = "GBP",
        balanceMinor = 200000,
        lastSynced = 0L,
        accountType = AccountType.PERSONAL,
    )

    /** Statements close on the 10th and are taken on the 1st; see CreditCardCycleTest. */
    private fun twoCycles() = listOf(
        tx("2026-06-01", -1000),
        tx("2026-06-20", -5000),
        tx("2026-07-05", -3000),
        tx("2026-07-09", -100),
        tx("2026-07-10", -700),
        tx("2026-08-01", 8800, declaredPayment = true),
        tx("2026-07-11", -200),
        tx("2026-07-20", -4000),
        tx("2026-08-09", -2000),
        tx("2026-09-01", 6200, declaredPayment = true),
        tx("2026-08-20", -2500), // on the statement that closed 10 Sep, due 1 Oct
        tx("2026-09-15", -1500), // since that close, so on the November bill
    )

    @Test
    fun `before it is paid, what is due is the statement`() {
        val bill = CreditCardEngine.analyze(twoCycles(), listOf(card(-4000)), LocalDate.of(2026, 9, 23))
            .bills.single()
        assertEquals(LocalDate.of(2026, 10, 1), bill.dueDate)
        assertEquals(2500L, bill.dueMinor)
    }

    /** Once the statement is settled, the next bill is whatever is owed and still growing. */
    @Test
    fun `after it is paid, what is due next is the rest`() {
        val paid = twoCycles() + tx("2026-10-01", 2500, declaredPayment = true)
        val bill = CreditCardEngine.analyze(paid, listOf(card(-1500)), LocalDate.of(2026, 10, 3))
            .bills.single()
        assertEquals(1500L, bill.dueMinor)
    }

    @Test
    fun `the budget holds back the statement, not the whole balance`() {
        val salary = listOf("2026-07-06", "2026-08-05", "2026-09-04")
            .map { tx(it, 250000, account = "current", payee = "ACME LTD SALARY") }
        val all = twoCycles() + salary
        val s = BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(current, card(-4000)),
            referenceTime = LocalDateTime.of(2026, 9, 23, 12, 0),
        )
        val cardBill = s.upcomingFixed.single { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) }
        assertEquals(2500L, cardBill.amountMinor)

        // Payday is the 6th, after this bill. The statement building now is due 1 November,
        // inside the next cycle, so that is what the next cycle carries.
        val bill = s.cardBills.single()
        assertEquals(bill.projectedMinor, s.cardsNextCycleMinor)
    }

    /**
     * The reported case: payday falls before the card's due date. The closed statement comes
     * out of the next cycle; the one building now is due the month after, so it belongs to the
     * cycle after that and is not piled on top. Adding both read £1,920 for two cards owing
     * £965 between them.
     */
    @Test
    fun `the next cycle carries only the card bills due inside it`() {
        val salary = listOf("2026-06-28", "2026-07-28", "2026-08-28")
            .map { tx(it, 250000, account = "current", payee = "ACME LTD SALARY") }
        val all = twoCycles() + salary
        val s = BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(current, card(-4000)),
            referenceTime = LocalDateTime.of(2026, 9, 23, 12, 0),
        )
        // Due 1 October, after payday on the 28th: not this cycle's, all next cycle's.
        assertTrue(s.upcomingFixed.none { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) })
        assertEquals(2500L, s.cardsNextCycleMinor)
    }
}
