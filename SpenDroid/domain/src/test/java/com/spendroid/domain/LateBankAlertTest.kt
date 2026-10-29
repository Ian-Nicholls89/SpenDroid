package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.domain.NotificationSpend.GOOGLE_WALLET
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.8.2: Wallet and a banking app each tell of a payment, in either order and however late -
 * NatWest once 53 minutes after Wallet. Each Wallet announcement takes one partner from a
 * banking app that covers its card, within a day, card digits or not.
 */
class LateBankAlertTest {

    private val natwest = "com.rbs.mobile.android.natwest"
    private val firstDirect = "com.firstdirect.bankingonthego"
    private fun at(h: Int, m: Int, day: Int = 16) = LocalDateTime.of(2026, 10, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun account(id: String, type: AccountType, last4: String) = AccountEntity(
        id = id, institutionName = "Bank", label = id, currency = "GBP", balanceMinor = 0, lastSynced = 0,
        accountType = type, walletLinked = true, cardLastFour = last4,
    )
    private val nectar = account("nectar", AccountType.CREDIT_CARD, "4168")
    private val personal = account("personal", AccountType.PERSONAL, "6627")
    private val joint = account("joint", AccountType.JOINT, "9001")
    private val setup = NotificationSpend.Setup(
        listOf(
            NotificationSpend.Source(natwest, "NatWest", listOf("nectar")),
            NotificationSpend.Source(firstDirect, "first direct", listOf("personal", "joint")),
        ),
        listOf(nectar, personal, joint),
    )

    private fun seen(id: String, source: String, time: Long, digits: String?, account: String?, amount: Long = 1_710) = SeenSpendEntity(
        id = id, source = source, seenAt = time, amountMinor = -amount, currency = "GBP",
        merchant = "Somewhere", cardDigits = digits, accountId = account,
    )

    private val wallet = seen("w", GOOGLE_WALLET, at(11, 53), "4168", "nectar")

    @Test
    fun `the reported case - NatWest 53 minutes after Wallet is the same lunch`() {
        assertEquals(wallet, NotificationSpend.duplicateOf(natwest, 1_710, "4168", at(12, 46), listOf(wallet), setup))
    }

    @Test
    fun `a bank alert with no card details pairs with Wallet on an account it covers`() {
        val coffee = seen("w2", GOOGLE_WALLET, at(9, 0), "6627", "personal", amount = 370)
        assertEquals(coffee, NotificationSpend.duplicateOf(firstDirect, 370, null, at(11, 30), listOf(coffee), setup))
        // first direct doesn't cover the Nectar card: never the same purchase.
        assertNull(NotificationSpend.duplicateOf(firstDirect, 1_710, null, at(12, 0), listOf(wallet), setup))
    }

    @Test
    fun `the bank first and Wallet after - the phone had no signal - is one purchase too`() {
        val bank = seen("n", natwest, at(11, 53), "4168", "nectar")
        assertEquals(bank, NotificationSpend.duplicateOf(GOOGLE_WALLET, 1_710, "4168", at(14, 10), listOf(bank), setup))
    }

    @Test
    fun `one partner each - a second round on the plastic card is a second spend`() {
        val partner = seen("n1", natwest, at(12, 46), "4168", null).copy(dismissed = true, pairedWithId = "w")
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, "4168", at(13, 30), listOf(wallet, partner), setup))
    }

    @Test
    fun `a day later, or another card, is another purchase`() {
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, "4168", at(12, 0, day = 17), listOf(wallet), setup))
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, "9999", at(12, 0), listOf(wallet), setup))
    }

    @Test
    fun `two banking apps still need to be within half an hour`() {
        val fd = seen("f", firstDirect, at(12, 0), null, "personal")
        assertNull(NotificationSpend.duplicateOf(natwest, 1_710, null, at(12, 45), listOf(fd)))
        assertEquals(fd, NotificationSpend.duplicateOf(natwest, 1_710, null, at(12, 20), listOf(fd)))
    }

    @Test
    fun `the twin of a purchase the bank took over is dropped`() {
        val reported = wallet.copy(matchedTransactionId = "bank-1")
        val late = seen("n", natwest, at(12, 46), "4168", "nectar")
        assertEquals(listOf(late to "w"), NotificationSpend.toDrop(listOf(reported, late), setup))
    }

    @Test
    fun `whichever one the bank took, the other goes`() {
        val late = seen("n", natwest, at(12, 46), "4168", "nectar").copy(matchedTransactionId = "bank-1")
        assertEquals(listOf(wallet to "n"), NotificationSpend.toDrop(listOf(wallet, late), setup))
        assertTrue(NotificationSpend.toDrop(listOf(wallet.copy(matchedTransactionId = "bank-2"), late), setup).isEmpty())
    }
}
