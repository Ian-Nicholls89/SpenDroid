package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.NotificationSpend.Placement
import com.spendroid.domain.NotificationSpend.Unsure
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.8: when a banking app covering two accounts doesn't say which paid - guess when sure, ask
 * when not, count it meanwhile, and let the user's "always put this shop here" win.
 */
class AccountAttributionTest {

    private val bank = "uk.co.example.bank"
    private var seq = 0

    private fun account(id: String, type: AccountType = AccountType.PERSONAL, last4: String? = null) = AccountEntity(
        id = id, institutionName = "Example", label = id, currency = "GBP", balanceMinor = 0, lastSynced = 0,
        accountType = type, cardLastFour = last4,
    )

    private fun tx(account: String, payee: String, minor: Long = -2_000) = TransactionEntity(
        accountId = account, transactionId = "t${seq++}", bookingDate = "2026-10-01", valueDate = null,
        amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )

    private val accounts = listOf(account("everyday", last4 = "1111"), account("joint", AccountType.JOINT, last4 = "2222"))
    private fun sources(unsure: Unsure = Unsure.SMART, default: String? = "everyday") =
        listOf(NotificationSpend.Source(bank, "Example", listOf("everyday", "joint"), default, unsure))

    private fun place(
        merchant: String,
        history: List<TransactionEntity>,
        unsure: Unsure = Unsure.SMART,
        digits: String? = null,
        places: Map<String, String> = emptyMap(),
    ) = NotificationSpend.placementFor(bank, NotificationSpend.Parsed(1_240, merchant, digits), sources(unsure), accounts, history, places)!!

    private val alwaysJoint = List(3) { tx("joint", "CO-OP") }

    @Test
    fun `a shop always paid from one account goes there without asking`() {
        assertEquals(Placement("joint", guessed = true), place("CO-OP", alwaysJoint))
    }

    @Test
    fun `seen only twice is not sure enough, so it asks`() {
        assertEquals(Placement(null, guessed = false, provisionalAccountId = "joint"), place("CO-OP", alwaysJoint.take(2)))
    }

    @Test
    fun `always ask asks even when sure`() {
        assertEquals(Placement(null, guessed = false, provisionalAccountId = "joint"), place("CO-OP", alwaysJoint, Unsure.ASK))
    }

    @Test
    fun `always one account means that account, as a certainty`() {
        assertEquals(Placement("everyday", guessed = false), place("CO-OP", alwaysJoint, Unsure.ALWAYS))
    }

    @Test
    fun `the shop the user placed goes there, whatever the setting`() {
        val places = mapOf(NotificationSpend.placeKey(bank, "Co-op ") to "everyday")
        assertEquals(Placement("everyday", guessed = false), place("CO-OP", alwaysJoint, Unsure.ASK, places = places))
    }

    @Test
    fun `card digits beat everything`() {
        val places = mapOf(NotificationSpend.placeKey(bank, "CO-OP") to "joint")
        assertEquals(Placement("everyday", guessed = false), place("CO-OP", alwaysJoint, digits = "1111", places = places))
    }

    private val at = LocalDateTime.of(2026, 10, 17, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val waiting = SeenSpendEntity(
        id = "s", source = bank, seenAt = at, amountMinor = -1_240, currency = "GBP", merchant = "CO-OP",
        provisionalAccountId = "joint",
    )

    @Test
    fun `waiting on an answer, it is counted on the likeliest account`() {
        assertEquals(listOf(waiting), NotificationSpend.counted(listOf(waiting)))
        assertEquals(listOf(waiting), NotificationSpend.unanswered(listOf(waiting)))
        assertEquals("joint", NotificationSpend.asTransaction(waiting, "Example", ZoneOffset.UTC).accountId)
        // It isn't also asked about as "not reported yet" a week on: it's already a question.
        assertTrue(NotificationSpend.unconfirmed(listOf(waiting), at + 8 * 24 * 3_600_000L).isEmpty())
    }

    @Test
    fun `answered, it is the account the user said`() {
        val answered = waiting.copy(accountId = "everyday", provisionalAccountId = null)
        assertTrue(NotificationSpend.unanswered(listOf(answered)).isEmpty())
        assertEquals("everyday", NotificationSpend.asTransaction(answered, "Example", ZoneOffset.UTC).accountId)
    }

    @Test
    fun `a payment from before 4_8 with no account is still not counted`() {
        val old = waiting.copy(provisionalAccountId = null)
        assertTrue(NotificationSpend.counted(listOf(old)).isEmpty())
        assertEquals(listOf(old), NotificationSpend.unanswered(listOf(old)))
    }
}
