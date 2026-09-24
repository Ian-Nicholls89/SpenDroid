package com.spendroid.ui

import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the transactions list shows, and what each chip does to it. */
class TransactionListFilterTest {

    private fun tx(id: String, amountMinor: Long, payee: String, transfer: Boolean = false, account: String = "current") =
        TransactionEntity(
            accountId = account,
            transactionId = id,
            bookingDate = "2026-09-20",
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = false,
            rawJson = null,
            isInternalTransfer = transfer,
        )

    private val lunch = tx("lunch", -450, "PRET")
    private val toSavings = tx("save-out", -50000, "MONTHLY SAVINGS", transfer = true)
    private val intoSavings = tx("save-in", 50000, "FROM CURRENT", transfer = true, account = "savings")
    private val cardBill = tx("card-pay", -5500, "TESCO BANK", transfer = true)
    private val all = listOf(lunch, toSavings, intoSavings, cardBill)

    private fun ids(filters: ListFilters) = visibleTransactions(
        all,
        filters,
        payers = setOf("current|card-pay"),
    ).map { it.transactionId }.toSet()

    @Test
    fun `the ordinary list leaves transfers out but keeps a card bill, which is real money leaving`() {
        assertEquals(setOf("lunch", "card-pay"), ids(ListFilters()))
    }

    /**
     * The reported bug: the chip put transfers back among everything else, so with a few in
     * hundreds of rows it looked as if it did nothing. It narrows now, like "Recurring only".
     */
    @Test
    fun `transfers only shows the transfers and nothing else`() {
        assertEquals(setOf("save-out", "save-in", "card-pay"), ids(ListFilters(transfersOnly = true)))
    }
}
