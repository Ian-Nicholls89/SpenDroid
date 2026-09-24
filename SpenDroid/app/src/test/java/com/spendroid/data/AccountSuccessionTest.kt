package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.AccountDetailsDto
import com.spendroid.data.remote.AccountInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reauthorising a bank issues every account a new id. Left alone, the old copy and the new
 * one both count the overlapping ninety days, and everything the user set on the old one -
 * its type, its name, the card's statement day - is on neither.
 */
class AccountSuccessionTest {

    private fun account(
        id: String,
        identity: String?,
        label: String = "Current ···1234",
        institution: String = "NatWest",
        type: AccountType = AccountType.PERSONAL,
    ) = AccountEntity(
        id = id,
        institutionName = institution,
        label = label,
        currency = "GBP",
        balanceMinor = 0,
        lastSynced = 0L,
        accountType = type,
        identity = identity,
    )

    private fun connection(requisition: String, vararg ids: String) =
        Connection("NATWEST", "NatWest", requisition, ids.toList(), createdAt = 1L)

    @Test
    fun `the same account under a new id is recognised by its identity`() {
        val old = account("old-1", "iban:GB00NWBK1234")
        val new = account("new-1", "iban:GB00NWBK1234")
        val pairs = GoCardlessRepository.matchSuccessors(
            newAccounts = listOf(new),
            others = listOf(old),
            otherConnections = emptyList(),
        )
        assertEquals(listOf(old to new), pairs)
    }

    /** A second login at the same bank is a different person's accounts, not a successor. */
    @Test
    fun `a different account at the same bank is left alone`() {
        val theirs = account("other-1", "iban:GB00NWBK9999")
        val mine = account("new-1", "iban:GB00NWBK1234")
        val pairs = GoCardlessRepository.matchSuccessors(
            listOf(mine),
            listOf(theirs),
            listOf(connection("req-other", "other-1")),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `another bank's account is never a successor`() {
        val elsewhere = account("old-1", "iban:GB00NWBK1234", institution = "Barclays")
        val pairs = GoCardlessRepository.matchSuccessors(
            listOf(account("new-1", "iban:GB00NWBK1234")),
            listOf(elsewhere),
            emptyList(),
        )
        assertTrue(pairs.isEmpty())
    }

    /**
     * A card often has no number the API will share, so the name has to do - but only among
     * accounts known to be from the consent being replaced, never one still in use.
     */
    @Test
    fun `without an identity, the name matches only an account the old consent held`() {
        val oldCurrent = account("old-1", "iban:GB00NWBK1234")
        val oldCard = account("old-card", null, label = "Credit Card", type = AccountType.CREDIT_CARD)
        val newCurrent = account("new-1", "iban:GB00NWBK1234")
        val newCard = account("new-card", null, label = "Credit Card")
        val oldConsent = connection("req-old", "old-1", "old-card")

        val pairs = GoCardlessRepository.matchSuccessors(
            listOf(newCurrent, newCard),
            listOf(oldCurrent, oldCard),
            listOf(oldConsent),
        )
        assertEquals(setOf(oldCurrent to newCurrent, oldCard to newCard), pairs.toSet())
    }

    @Test
    fun `a name shared by an account still in use is not enough`() {
        val stillLive = account("live-card", null, label = "Credit Card")
        val pairs = GoCardlessRepository.matchSuccessors(
            listOf(account("new-card", null, label = "Credit Card")),
            listOf(stillLive),
            listOf(connection("req-live", "live-card")),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `two candidates is a guess, and guessing is declined`() {
        val a = account("old-a", "iban:GB00NWBK1234")
        val b = account("old-b", "iban:GB00NWBK1234")
        val pairs = GoCardlessRepository.matchSuccessors(
            listOf(account("new-1", "iban:GB00NWBK1234")),
            listOf(a, b),
            emptyList(),
        )
        assertTrue(pairs.isEmpty())
    }

    private fun tx(account: String, id: String, date: String, category: String? = null) = TransactionEntity(
        accountId = account,
        transactionId = id,
        bookingDate = date,
        valueDate = null,
        amountMinor = -1000,
        currency = "GBP",
        payee = "SHOP",
        description = null,
        isPending = false,
        rawJson = null,
        categoryOverride = category,
    )

    /**
     * History from before the new consent's window exists only on the old account and has to
     * move across; inside the window the fresh fetch is the authority, but what the user
     * decided about those rows still carries over.
     */
    @Test
    fun `older history moves across and the overlap is not doubled`() {
        val old = listOf(
            tx("old-1", "a", "2026-03-01"),
            tx("old-1", "b", "2026-07-01", category = "WORK_LUNCH"),
            tx("old-1", "c", "2026-07-02"),
        )
        val fresh = listOf(
            tx("new-1", "b", "2026-07-01"),
            tx("new-1", "d", "2026-09-01"),
        )
        val merged = GoCardlessRepository.succeededHistory(old, fresh, "new-1")

        assertEquals(listOf("a", "b", "d"), merged.map { it.transactionId }.sorted())
        assertTrue(merged.all { it.accountId == "new-1" })
        assertEquals("WORK_LUNCH", merged.single { it.transactionId == "b" }.categoryOverride)
    }

    @Test
    fun `identity prefers the IBAN, then the UK number, then the card`() {
        assertEquals(
            "iban:GB29NWBK60161331926819",
            GoCardlessRepository.identityFor(
                AccountDetailsDto(),
                AccountInfoDto(iban = "GB29 NWBK 6016 1331 9268 19", sortCode = "601613", accountNumber = "31926819"),
            ),
        )
        assertEquals(
            "uk:601613/31926819",
            GoCardlessRepository.identityFor(AccountDetailsDto(), AccountInfoDto(sortCode = "60-16-13", accountNumber = "31926819")),
        )
        assertEquals(
            "pan:4929XXXXXXXX1234",
            GoCardlessRepository.identityFor(AccountDetailsDto(), AccountInfoDto(maskedPan = "4929XXXXXXXX1234")),
        )
        assertNull(GoCardlessRepository.identityFor(AccountDetailsDto(), AccountInfoDto()))
    }
}
