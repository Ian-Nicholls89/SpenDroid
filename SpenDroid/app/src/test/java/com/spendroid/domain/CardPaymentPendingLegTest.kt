package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 5.2.3, the reported case: the Nectar bill's payment booked on the card while the direct debit
 * from the current account was still pending, so the payment read as a refund and cancelled
 * every charge since the statement closed - "£194.10 so far", the pending charges alone.
 */
class CardPaymentPendingLegTest {

    private var seq = 0
    private fun tx(account: String, date: String, amount: Long, payee: String, pending: Boolean = false) = TransactionEntity(
        accountId = account, transactionId = "t${seq++}", bookingDate = date, valueDate = null, amountMinor = amount,
        currency = "GBP", payee = payee, description = null, isPending = pending, rawJson = null,
    )

    private val card = AccountEntity(
        id = "nectar", institutionName = "Natwest", label = "Nectar Credit Card", currency = "GBP", balanceMinor = -50000,
        lastSynced = 0L, accountType = AccountType.CREDIT_CARD, statementDayOfMonth = 23, paymentDayOfMonth = 17,
    )
    private val current = AccountEntity(
        id = "personal", institutionName = "Natwest", label = "Personal Account", currency = "GBP", balanceMinor = 100000,
        lastSynced = 0L, accountType = AccountType.PERSONAL,
    )

    private val since = listOf(
        tx("nectar", "2026-09-29", -3565, "SALT DELI"), tx("nectar", "2026-10-06", -3565, "SALT DELI"),
        tx("nectar", "2026-10-09", -2869, "EBAY"), tx("nectar", "2026-10-12", -605, "GREGGS"),
        tx("nectar", "2026-10-18", -6350, "WAITROSE STORES", pending = true),
    )

    private fun bill(extra: List<TransactionEntity>) =
        CreditCardEngine.analyze(since + extra, listOf(card, current), LocalDate.parse("2026-10-19")).bills.single()

    @Test
    fun `a payment whose debit is still pending is a payment, not a refund`() {
        val b = bill(
            listOf(
                tx("nectar", "2026-10-16", 95976, "PAYMENT RECEIVED"),
                tx("personal", "2026-10-19", -95976, "NECTAR CARD DD", pending = true),
            ),
        )
        assertEquals(3565L + 3565L + 2869L + 605L + 6350L, b.unbilledMinor)
        assertEquals(6350L, b.pendingMinor)
    }

    @Test
    fun `last month's payment teaches its wording to this month's`() {
        val b = bill(
            listOf(
                tx("nectar", "2026-09-17", 129420, "PAYMENT RECEIVED"),
                tx("personal", "2026-09-17", -129420, "NECTAR CARD DD"),
                // This month's: on the card, its debit not listed at all yet.
                tx("nectar", "2026-10-16", 95976, "PAYMENT RECEIVED"),
            ),
        )
        assertEquals(3565L + 3565L + 2869L + 605L + 6350L, b.unbilledMinor)
    }

    @Test
    fun `a real refund still comes off`() {
        val b = bill(listOf(tx("nectar", "2026-10-10", 2000, "REFUND ARGOS")))
        assertEquals(3565L + 3565L + 2869L + 605L - 2000L + 6350L, b.unbilledMinor)
    }
}
