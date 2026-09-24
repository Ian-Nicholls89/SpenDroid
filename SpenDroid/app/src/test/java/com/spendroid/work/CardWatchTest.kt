package com.spendroid.work

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.CreditCardEngine
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With card purchases counted at the bill, the card is watched on its own clock instead:
 * statement to statement, against the user's limit or its usual bill.
 */
class CardWatchTest {

    private val today = LocalDate.of(2026, 9, 23)
    private var seq = 0

    private fun tx(date: String, amountMinor: Long, declaredPayment: Boolean = false) = TransactionEntity(
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
        isCardPayment = declaredPayment,
    )

    private fun card(balanceMinor: Long, cap: Long? = null) = AccountEntity(
        id = "card",
        institutionName = "Tesco Bank",
        label = "Tesco Card",
        currency = "GBP",
        balanceMinor = balanceMinor,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
        spendingCapMinor = cap,
    )

    /** Statements close on the 10th, taken on the 1st - the fixture from CreditCardCycleTest. */
    private fun history(vararg extra: TransactionEntity) = listOf(
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
        tx("2026-08-20", -2500),
        tx("2026-09-15", -1500),
    ) + extra

    private fun bill(txs: List<TransactionEntity>, balance: Long, cap: Long? = null) =
        CreditCardEngine.analyze(txs, listOf(card(balance, cap)), today).bills.single()

    @Test
    fun `the usual bill is the middle of the bills paid`() {
        assertEquals((8800L + 6200L) / 2, bill(history(), -4000).usualBillMinor)
    }

    /** £15 in the four weeks to today, carried seventeen more days to the 10 October close. */
    @Test
    fun `the statement is projected at the recent rate`() {
        val b = bill(history(), -4000)
        assertEquals(LocalDate.of(2026, 10, 10), b.nextStatementClose)
        assertEquals(1500L + 1500L * 17 / 28, b.projectedMinor)
    }

    @Test
    fun `a card with under a week of history is not projected`() {
        val fresh = listOf(tx("2026-09-20", -1000))
        assertNull(CreditCardEngine.projectStatement(fresh, emptySet(), 1000L, today, LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun `the user's limit wins over the usual bill`() {
        val b = bill(history(), -4000, cap = 2000)
        assertEquals(2000L, b.capMinor)
        assertEquals(CreditCardEngine.CapSource.USER, b.capSource)
    }

    @Test
    fun `approaching the limit is said once, and passing it again`() {
        val near = bill(history(), -4000, cap = 1800) // £15 of £18
        val first = CardWatch.warnings(listOf(near), emptySet())
        assertTrue(first.messages.any { "83% of your £18.00" in it })

        val again = CardWatch.warnings(listOf(near), first.keys)
        assertTrue("said once per statement", again.messages.none { "83%" in it })

        val past = bill(history(tx("2026-09-22", -500)), -4500, cap = 1800)
        val over = CardWatch.warnings(listOf(past), again.keys)
        assertTrue(over.messages.any { "past your £18.00, at £20.00" in it })
    }

    @Test
    fun `heading over the limit is said while there is still time`() {
        // £15 so far against £20, but on pace for about £24.
        val b = bill(history(), -4000, cap = 2000)
        val w = CardWatch.warnings(listOf(b), emptySet())
        assertTrue(w.messages.single().startsWith("Tesco Card is on pace for about £24.10 by the 10 Oct statement"))
    }

    @Test
    fun `a new statement starts afresh`() {
        val b = bill(history(), -4000, cap = 1800)
        val stale = setOf("card|0.8|2026-08-10")
        val w = CardWatch.warnings(listOf(b), stale)
        assertTrue("card|0.8|2026-08-10" !in w.keys)
        assertTrue(w.messages.isNotEmpty())
    }
}
