package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reported case: the Nectar card's statement closing 23 Sep 2026 was £1,294.20, and the
 * app said £791.00 - the bank's stale reported balance of £855.80 less £64.80 spent since.
 * The statement is its period's charges added up, and every one of them was in the app.
 */
class StatementTotalTest {

    private var seq = 0
    private fun tx(date: String, amount: Long, payee: String = "SHOP", payment: Boolean = false) = TransactionEntity(
        accountId = "nectar",
        transactionId = "t${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amount,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
        isCardPayment = payment,
    )

    /** The statement's own lines, 24 Aug to 23 Sep. */
    private val statement = listOf(
        "2026-08-24" to 2487, "2026-08-24" to 4849, "2026-08-24" to 1800, "2026-08-24" to 2975,
        "2026-08-24" to 100, "2026-08-24" to 4489, "2026-08-27" to 3565, "2026-08-27" to 8900,
        "2026-08-28" to 560, "2026-08-28" to 3099, "2026-08-31" to 3240, "2026-08-31" to 21490,
        "2026-08-31" to 2380, "2026-08-31" to 280, "2026-08-31" to 700, "2026-09-01" to 4296,
        "2026-09-01" to 1641, "2026-09-01" to 6125, "2026-09-02" to 1500, "2026-09-03" to 1899,
        "2026-09-03" to 3565, "2026-09-04" to 425, "2026-09-07" to 3282, "2026-09-07" to 4760,
        "2026-09-08" to 1223, "2026-09-08" to 360, "2026-09-08" to 160, "2026-09-08" to 200,
        "2026-09-09" to 899, "2026-09-09" to 4140, "2026-09-09" to 6870, "2026-09-10" to 12787,
        "2026-09-11" to 770, "2026-09-17" to 990, "2026-09-17" to 259, "2026-09-18" to 1800,
        "2026-09-21" to 2600, "2026-09-21" to 3830, "2026-09-21" to 360, "2026-09-21" to 200,
        "2026-09-23" to 3565,
    ).map { (d, a) -> tx(d, -a.toLong()) }

    private val card = AccountEntity(
        id = "nectar",
        institutionName = "Natwest",
        label = "Nectar Credit Card",
        currency = "GBP",
        // What the bank reported: stale, and £438.40 short of the statement.
        balanceMinor = -85580,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
        statementDayOfMonth = 23,
        paymentDayOfMonth = 17,
    )

    private fun bill(extra: List<TransactionEntity> = emptyList(), today: String = "2026-09-26") =
        CreditCardEngine.analyze(
            // Last month's bill paid on the 17th, inside this period, and not part of it.
            statement + tx("2026-09-17", 95976, payee = "PAYMENT RECEIVED", payment = true) +
                tx("2026-08-20", -5000) + extra,
            listOf(card),
            LocalDate.parse(today),
        ).bills.single()

    @Test
    fun `the statement is its period's charges added up`() {
        val b = bill(listOf(tx("2026-09-25", -6480)))
        assertEquals(129420L, b.billedMinor)
        assertEquals(6480L, b.unbilledMinor)
        assertEquals(129420L, b.dueMinor)
        assertEquals(129420L + 6480L, b.outstandingMinor)
    }

    @Test
    fun `a refund in the period comes off, as it does on the statement`() {
        assertEquals(129420L - 2000L, bill(listOf(tx("2026-09-05", 2000, payee = "REFUND ARGOS"))).billedMinor)
    }

    /** Once the statement is paid, nothing more is owed on it. */
    @Test
    fun `paying the statement settles it`() {
        val paid = bill(listOf(tx("2026-10-17", 129420, payee = "PAYMENT RECEIVED", payment = true)), today = "2026-10-18")
        assertEquals(0L, paid.billedMinor)
    }
}
