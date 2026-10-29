package com.spendroid.domain

import com.spendroid.data.db.SeenSpendEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 5.5.1: a payment under 10p is asked about - a card check, or real? - until answered or reported. */
class CardCheckTest {

    private fun seen(id: String, minor: Long, f: (SeenSpendEntity) -> SeenSpendEntity = { it }) = f(
        SeenSpendEntity(id = id, source = "bank", seenAt = 0L, amountMinor = -minor, currency = "GBP", merchant = "Car Park", accountId = "a"),
    )

    @Test
    fun `only tiny payments still waiting are asked about`() {
        val all = listOf(
            seen("one", 1), seen("nine", 9), seen("ten", 10), seen("pound", 100),
            seen("checked", 1) { it.copy(dismissed = true) },
            seen("real", 1) { it.copy(keptByUser = true) },
            seen("reported", 1) { it.copy(matchedTransactionId = "tx") },
        )
        assertEquals(listOf("one", "nine"), NotificationSpend.cardChecks(all).map { it.id })
    }
}
