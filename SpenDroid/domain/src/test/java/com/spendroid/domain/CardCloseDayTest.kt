package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5.7.5, the reported case: the Nectar statement closed on 23 Oct, and three purchases made
 * that day - still pending - showed nowhere: not on the statement that closed, not on the next.
 */
class CardCloseDayTest {

    private var seq = 0
    private fun tx(date: String, amount: Long, payee: String, pending: Boolean = false) = TransactionEntity(
        accountId = "nectar", transactionId = "t${seq++}", bookingDate = date, valueDate = null, amountMinor = amount,
        currency = "GBP", payee = payee, description = null, isPending = pending, rawJson = null,
    )

    private val card = AccountEntity(
        id = "nectar", institutionName = "Natwest", label = "Nectar Credit Card", currency = "GBP", balanceMinor = -53920,
        lastSynced = 0L, accountType = AccountType.CREDIT_CARD, statementDayOfMonth = 23, paymentDayOfMonth = 17,
    )

    @Test
    fun `pending on the closing day is the next statement's`() {
        val bill = CreditCardEngine.analyze(
            listOf(
                tx("2026-10-20", -2500, "TESCO"),
                tx("2026-10-23", -650, "REEVE THE BAKER", pending = true),
                tx("2026-10-23", -200, "OBN*MIPERMIT", pending = true),
                tx("2026-10-23", -360, "OBN*MIPERMIT", pending = true),
            ),
            listOf(card), LocalDate.parse("2026-10-23"),
        ).bills.single()
        assertEquals(LocalDate.parse("2026-10-23"), bill.statementClose)
        assertEquals(1210L, bill.unbilledMinor)
        assertEquals(1210L, bill.pendingMinor)
    }
}
