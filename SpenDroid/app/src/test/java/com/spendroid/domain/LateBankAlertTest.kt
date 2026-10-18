package com.spendroid.domain

import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.domain.NotificationSpend.GOOGLE_WALLET
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.8.1, the reported case: Wallet told of a £17.10 lunch at 11:53, NatWest's app at 12:46,
 * both on the card ending 4168. Fifty-three minutes apart, it counted twice.
 */
class LateBankAlertTest {

    private val natwest = "com.rbs.mobile.android.natwest"
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 16, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun seen(id: String, source: String, time: Long, digits: String?, matched: String? = null) = SeenSpendEntity(
        id = id, source = source, seenAt = time, amountMinor = -1_710, currency = "GBP",
        merchant = "SumUp *Pie and Piper", cardDigits = digits, accountId = "nectar", matchedTransactionId = matched,
    )

    private val wallet = seen("w", GOOGLE_WALLET, at(11, 53), "4168")

    @Test
    fun `the same card's digits make it one purchase within three hours`() {
        assertEquals(wallet, NotificationSpend.duplicateOf(natwest, 1_710, "4168", at(12, 46), listOf(wallet)))
        // Another card is another purchase, however close.
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, "9999", at(11, 55), listOf(wallet)))
        // Hours later, a second round.
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, "4168", at(15, 30), listOf(wallet)))
    }

    @Test
    fun `without digits on both, half an hour is still the limit`() {
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, null, at(12, 46), listOf(wallet)))
        assertEquals(wallet, NotificationSpend.duplicateOf(natwest, 1_710, null, at(12, 10), listOf(wallet)))
    }

    @Test
    fun `the twin of a purchase the bank took over is dropped`() {
        val reported = wallet.copy(matchedTransactionId = "bank-1")
        val late = seen("n", natwest, at(12, 46), "4168")
        assertEquals(listOf(late), NotificationSpend.toDrop(listOf(reported, late)))
    }

    @Test
    fun `whichever one the bank took, the other goes`() {
        val late = seen("n", natwest, at(12, 46), "4168", matched = "bank-1")
        assertEquals(listOf(wallet), NotificationSpend.toDrop(listOf(wallet, late)))
        // Both reported: nothing to drop.
        assertTrue(NotificationSpend.toDrop(listOf(wallet.copy(matchedTransactionId = "bank-2"), late)).isEmpty())
    }
}
