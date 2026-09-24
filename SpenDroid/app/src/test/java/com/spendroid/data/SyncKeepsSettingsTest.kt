package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A sync is the bank's chance to update what the bank knows. It has no say over what the user
 * set, and every column it rebuilt from scratch was one the user lost overnight.
 */
class SyncKeepsSettingsTest {

    private val stored = AccountEntity(
        id = "card-1",
        institutionName = "Tesco Bank",
        label = "Tesco Card",
        currency = "GBP",
        balanceMinor = -10000,
        lastSynced = 1L,
        accountType = AccountType.CREDIT_CARD,
        linkedCreditCardAccountId = "current",
        rawBalancesJson = "{}",
        statementDayOfMonth = 12,
        paymentDayOfMonth = 5,
    )

    private val fetched = AccountEntity(
        id = "card-1",
        institutionName = "Tesco Bank",
        label = "Credit Card ···1234",
        currency = "GBP",
        balanceMinor = -25000,
        lastSynced = 99L,
        accountType = AccountType.PERSONAL,
        rawBalancesJson = "{\"balances\":[]}",
    )

    @Test
    fun `the card's statement and payment days survive a sync`() {
        val merged = GoCardlessRepository.refreshedAccount(stored, fetched)
        assertEquals(12, merged.statementDayOfMonth)
        assertEquals(5, merged.paymentDayOfMonth)
    }

    @Test
    fun `the user's type, name and link survive a sync`() {
        val merged = GoCardlessRepository.refreshedAccount(stored, fetched)
        assertEquals(AccountType.CREDIT_CARD, merged.accountType)
        assertEquals("Tesco Card", merged.label)
        assertEquals("current", merged.linkedCreditCardAccountId)
    }

    @Test
    fun `what the bank knows is refreshed`() {
        val merged = GoCardlessRepository.refreshedAccount(stored, fetched)
        assertEquals(-25000L, merged.balanceMinor)
        assertEquals(99L, merged.lastSynced)
        assertEquals("{\"balances\":[]}", merged.rawBalancesJson)
    }

    @Test
    fun `a new account is taken as fetched`() {
        assertEquals(fetched, GoCardlessRepository.refreshedAccount(null, fetched))
    }

    /**
     * Without an id from the bank, a row is named after what it contains - so two identical
     * coffees on the same day were the same row, and one of them vanished.
     */
    @Test
    fun `identical purchases on the same day stay separate`() {
        val first = GoCardlessRepository.fallbackTransactionId("2026-09-20", -320, "PRET", 1)
        val second = GoCardlessRepository.fallbackTransactionId("2026-09-20", -320, "PRET", 2)
        assertNotEquals(first, second)
    }

    /** Rows stored before this existed were keyed the old way; the first must still match. */
    @Test
    fun `the first of a kind keeps the id it always had`() {
        assertEquals(
            "2026-09-20|-320|PRET".hashCode().toString(),
            GoCardlessRepository.fallbackTransactionId("2026-09-20", -320, "PRET", 1),
        )
    }
}
