package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The card figures disagreed with the accounts screen because they came from a different
 * source - a sum of the last 90 days rather than the bank's balance. These pin down the
 * rule that replaced it: the bank says how much is owed, the solved cycle says how it splits.
 */
class CreditCardCycleTest {

    private val today = LocalDate.of(2026, 9, 23)

    private fun card(
        balanceMinor: Long?,
        statementDay: Int? = null,
        paymentDay: Int? = null,
    ) = AccountEntity(
        id = "card",
        institutionName = "Tesco Bank",
        label = "Tesco Credit Card",
        currency = "GBP",
        balanceMinor = balanceMinor,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
        statementDayOfMonth = statementDay,
        paymentDayOfMonth = paymentDay,
    )

    private var seq = 0

    private fun tx(date: String, amountMinor: Long) = TransactionEntity(
        accountId = "card",
        transactionId = "tx-${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = if (amountMinor > 0) "PAYMENT RECEIVED" else "SHOP",
        description = null,
        isPending = false,
        rawJson = null,
    )

    /**
     * Two statements, both cleared in full, closing on the 10th and taken on the 1st. Only
     * a closing day of 10 rebuilds both bills exactly, so that is the one to find.
     */
    private fun twoCycles() = listOf(
        tx("2026-06-01", -1000), // before the first full window; fixes the data's start
        tx("2026-06-20", -5000),
        tx("2026-07-05", -3000),
        // Charges on consecutive days across the boundary are what pin it down: move the
        // close by one day either way and these land on the wrong statement.
        tx("2026-07-09", -100),
        tx("2026-07-10", -700),
        tx("2026-08-01", 8800), // pays the statement that closed 10 Jul
        tx("2026-07-11", -200),
        tx("2026-07-20", -4000),
        tx("2026-08-09", -2000),
        tx("2026-09-01", 6200), // pays the statement that closed 10 Aug
        tx("2026-08-20", -2500), // on the statement that closed 10 Sep, due 1 Oct
        tx("2026-09-15", -1500), // since that close, so not yet billed
    )

    @Test
    fun `the statement day is solved by replaying past bills`() {
        val analysis = CreditCardEngine.analyze(twoCycles(), listOf(card(-4000)), today)
        val bill = analysis.bills.single()

        assertEquals(10, bill.statementDay)
        assertEquals(CreditCardEngine.CycleSource.INFERRED, bill.cycleSource)
        assertEquals(LocalDate.of(2026, 9, 10), bill.statementClose)
        assertEquals(0L, bill.cycleFitErrorMinor)
    }

    @Test
    fun `the solved cycle splits the balance into billed and still accruing`() {
        val bill = CreditCardEngine.analyze(twoCycles(), listOf(card(-4000)), today).bills.single()

        assertEquals(4000L, bill.outstandingMinor)
        assertEquals(2500L, bill.billedMinor)
        assertEquals(1500L, bill.unbilledMinor)
    }

    /**
     * The reported bug. A balance carried from before the 90-day window is invisible to any
     * sum of transactions, so the total has to come from the bank or the two screens differ.
     */
    @Test
    fun `the total is the bank balance, not a sum of the visible transactions`() {
        val transactions = listOf(tx("2026-09-15", -8790))
        val bill = CreditCardEngine.analyze(transactions, listOf(card(-85580)), today)
            .bills.single()

        assertEquals(85580L, bill.outstandingMinor)
        assertEquals(CreditCardEngine.TotalSource.BANK_BALANCE, bill.totalSource)
    }

    @Test
    fun `billed and unbilled always add back up to the total`() {
        listOf(-4000L, -85580L, -100L, 0L).forEach { balance ->
            val bill = CreditCardEngine.analyze(twoCycles(), listOf(card(balance)), today)
                .bills.single()
            assertEquals(
                "balance $balance",
                bill.outstandingMinor,
                bill.billedMinor + bill.unbilledMinor,
            )
        }
    }

    /**
     * Clearing the statement settles what closed; it must not cancel out charges made since,
     * which netting the whole window would do.
     */
    @Test
    fun `a payment made after the close does not wipe out charges made since`() {
        val transactions = listOf(
            tx("2026-09-05", -2500), // on the statement that closed 10 Sep
            tx("2026-09-15", 2500), // clears it
            tx("2026-09-18", -1500), // after the close, so still accruing
        )
        val bill = CreditCardEngine
            .analyze(transactions, listOf(card(-1500, statementDay = 10, paymentDay = 15)), today)
            .bills.single()

        assertEquals(1500L, bill.unbilledMinor)
        assertEquals(0L, bill.billedMinor)
    }

    @Test
    fun `a day the user set beats the one that was solved`() {
        val bill = CreditCardEngine
            .analyze(twoCycles(), listOf(card(-4000, statementDay = 20)), today)
            .bills.single()

        assertEquals(20, bill.statementDay)
        assertEquals(CreditCardEngine.CycleSource.USER, bill.cycleSource)
        assertEquals(LocalDate.of(2026, 9, 20), bill.statementClose)
    }

    /**
     * A card that revolves never pays an amount matching a window of charges, so nothing
     * fits. Saying so is what prompts the user to fill the cycle in by hand.
     */
    @Test
    fun `a card that is never cleared in full reports no solved cycle`() {
        val transactions = listOf(
            tx("2026-06-15", -20000),
            tx("2026-07-15", -18000),
            tx("2026-07-20", 5000), // a flat part-payment, unrelated to any statement
            tx("2026-08-20", 5000),
        )
        val bill = CreditCardEngine.analyze(transactions, listOf(card(-33000)), today).bills.single()

        assertEquals(CreditCardEngine.CycleSource.ASSUMED, bill.cycleSource)
        assertEquals(33000L, bill.outstandingMinor)
    }
}
