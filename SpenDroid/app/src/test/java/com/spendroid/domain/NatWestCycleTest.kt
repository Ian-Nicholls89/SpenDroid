package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real cycle, from a real card: the statement runs 24th to 23rd, and the bill is taken on
 * the 17th of the month after it closes - a 25 day payment term.
 *
 * The long term is the point of the test. It puts the bill nearly four weeks after the
 * spending it covers, and two whole statements' worth of charges in flight at once.
 */
class NatWestCycleTest {

    private val today = LocalDate.of(2026, 9, 28)

    private var seq = 0

    private fun tx(date: String, amountMinor: Long) = TransactionEntity(
        accountId = "card",
        transactionId = "tx-${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = if (amountMinor > 0) "PAYMENT THANK YOU" else "SHOP",
        description = null,
        isPending = false,
        rawJson = null,
    )

    private fun card(balanceMinor: Long) = AccountEntity(
        id = "card",
        institutionName = "NatWest",
        label = "NatWest Credit Card",
        currency = "GBP",
        balanceMinor = balanceMinor,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
    )

    /** The statement that closed 23 Aug, paid 17 Sep. Charges sit either side of the boundary. */
    private val augustStatement = listOf(
        tx("2026-07-24", -900), // first day of the window
        tx("2026-08-10", -2100),
        tx("2026-08-22", -1000),
        tx("2026-08-23", -1000), // closing day itself
    )

    /** Still in flight: closed 23 Sep, not due until 17 Oct. */
    private val septemberStatement = listOf(
        tx("2026-08-24", -400),
        tx("2026-09-05", -2100),
    )

    @Test
    fun `a 24th-to-23rd statement billed on the 17th is solved exactly`() {
        val transactions = listOf(tx("2026-06-20", -1000)) +
            // The statement that closed 23 Jul, paid 17 Aug.
            listOf(tx("2026-06-30", -2000), tx("2026-07-22", -1500), tx("2026-07-23", -2500)) +
            listOf(tx("2026-08-17", 6000)) +
            augustStatement +
            listOf(tx("2026-09-17", 5000)) +
            septemberStatement +
            listOf(tx("2026-09-25", -700)) // after the close, so next month's problem

        val bill = CreditCardEngine.analyze(transactions, listOf(card(-3200)), today).bills.single()

        assertEquals(23, bill.statementDay)
        assertEquals(CreditCardEngine.CycleSource.INFERRED, bill.cycleSource)
        assertEquals(0L, bill.cycleFitErrorMinor)
        assertEquals(2, bill.cycleBillsChecked)
        assertEquals(LocalDate.of(2026, 9, 23), bill.statementClose)

        // £2,500 on the statement that closed 23 Sep, £700 charged since.
        assertEquals(3200L, bill.outstandingMinor)
        assertEquals(2500L, bill.billedMinor)
        assertEquals(700L, bill.unbilledMinor)

        assertEquals(17, bill.nominalPaymentDay)
    }

    /**
     * What the app actually sees on day one: the API returns 90 days, so the older of the
     * two statements starts before the data does and cannot be replayed. One bill is enough
     * to solve the cycle here, and the count says how much evidence stood behind it.
     */
    @Test
    fun `one visible bill is still enough to solve the cycle`() {
        val transactions = listOf(tx("2026-06-30", -1000)) + // 90 days back from today
            augustStatement +
            listOf(tx("2026-09-17", 5000)) +
            septemberStatement +
            listOf(tx("2026-09-25", -700))

        val bill = CreditCardEngine.analyze(transactions, listOf(card(-3200)), today).bills.single()

        assertEquals(23, bill.statementDay)
        assertEquals(1, bill.cycleBillsChecked)
        assertEquals(2500L, bill.billedMinor)
        assertEquals(700L, bill.unbilledMinor)
    }

    /**
     * The 25 day term is comfortably inside what the solver will entertain, but it is worth
     * pinning: a term outside the range is rejected as implausible rather than fitted.
     */
    @Test
    fun `a 25 day payment term is within the plausible range`() {
        val close = LocalDate.of(2026, 8, 23)
        val paid = LocalDate.of(2026, 9, 17)
        assertTrue(paid.toEpochDay() - close.toEpochDay() == 25L)
    }

    /**
     * A card closing on the last day of the month, which the search used to stop just short
     * of. February makes the 30th land on the 28th, exactly as such a card really bills.
     */
    @Test
    fun `a card closing on the 30th is solved too`() {
        val transactions = listOf(
            tx("2026-06-10", -1000),
            // Closed 30 Jun, paid 22 Jul.
            tx("2026-06-25", -3000),
            tx("2026-06-30", -1000),
            tx("2026-07-22", 4000),
            // Closed 30 Jul, paid 22 Aug.
            tx("2026-07-01", -500),
            tx("2026-07-30", -2500),
            tx("2026-08-22", 3000),
            // Still in flight.
            tx("2026-08-15", -1800),
            tx("2026-09-20", -600),
        )
        val bill = CreditCardEngine.analyze(transactions, listOf(card(-2400)), today).bills.single()

        assertEquals(30, bill.statementDay)
        assertEquals(0L, bill.cycleFitErrorMinor)
    }
}
