package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** first direct's wording, from the user's phone (28 Sep 2026), and teaching from examples. */
class NotificationLearningTest {

    private val fd = "com.firstdirect.bankingonthego"

    @Test
    fun `first direct's money out is read, and money in is not`() {
        val out = NotificationSpend.parse(fd, "first direct", "Money out: £16.86 to UNITE THE UNION")!!
        assertEquals(1686L, out.amountMinor)
        assertEquals("UNITE THE UNION", out.merchant)
        assertNull(NotificationSpend.parse(fd, "first direct", "Money in: £500.00, BILLS"))
        assertNull(NotificationSpend.parse(fd, "first direct", "Money in: £108.20, HMRC CHILD BENEFIT"))
    }

    @Test
    fun `a wording taught once reads every notification like it`() {
        val learned = NotificationSpend.learn("bank", "My Bank", "Spent £3.50 at COSTA on card 1234 at 09:14", count = true)!!
        val next = NotificationSpend.parse("bank", "My Bank", "Spent £12.00 at PRET A MANGER on card 1234 at 17:02", listOf(learned))!!
        assertEquals(1200L, next.amountMinor)
        assertEquals("PRET A MANGER", next.merchant)
    }

    /** A wording no built-in rule reads, learned for one app only. */
    @Test
    fun `a learned wording belongs to its own app`() {
        val learned = NotificationSpend.learn("bank", "My Bank", "Coffee: £3.50, COSTA", count = true)!!
        assertEquals(420L, NotificationSpend.parse("bank", "My Bank", "Coffee: £4.20, PRET", listOf(learned))!!.amountMinor)
        assertNull(NotificationSpend.parse("other", "My Bank", "Coffee: £4.20, PRET", listOf(learned)))
    }

    @Test
    fun `a payee at the end of the line is learned too`() {
        val learned = NotificationSpend.learn(fd, "first direct", "Payment: £9.99 to SPOTIFY", count = true)!!
        val next = NotificationSpend.parse(fd, "first direct", "Payment: £61.25 to TUMBLETOTS SALISBURY", listOf(learned))!!
        assertEquals(6125L, next.amountMinor)
        assertEquals("TUMBLETOTS SALISBURY", next.merchant)
    }

    @Test
    fun `an ignore taught wins over the built-in rules`() {
        val ignore = NotificationSpend.learn(fd, "first direct", "Money out: £50.00 to PREMIUM BONDS NS&I", count = false)!!
        assertNull(NotificationSpend.parse(fd, "first direct", "Money out: £25.00 to PREMIUM BONDS NS&I", listOf(ignore)))
        // Worded differently, the built-in rule still reads it.
        assertEquals(425L, NotificationSpend.parse(fd, "first direct", "Money out: £4.25 to GREGGS", listOf(ignore))!!.amountMinor)
    }

    @Test
    fun `nothing to learn without a single amount`() {
        assertNull(NotificationSpend.learn(fd, "first direct", "Your statement is ready", count = true))
    }

    // --- the budget already has it ---

    private var seq = 0
    private fun tx(account: String, amount: Long, payee: String, transfer: Boolean = false, date: String = "2026-09-20") =
        TransactionEntity(
            accountId = account, transactionId = "t${seq++}", bookingDate = date, valueDate = null, amountMinor = amount,
            currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null, isInternalTransfer = transfer,
        )

    @Test
    fun `a payee that is always a transfer, or a regular bill, is not counted again`() {
        val history = listOf(
            tx("personal", -50_000, "JOINT ACCOUNT BILLS", transfer = true),
            tx("personal", -50_000, "JOINT ACCOUNT BILLS", transfer = true),
        )
        assertTrue(NotificationSpend.alreadyAccountedFor(NotificationSpend.Parsed(50_000, "JOINT ACCOUNT BILLS", null), history, emptyList()))

        val union = listOf("2026-07-28", "2026-08-28", "2026-09-28").map { tx("personal", -1686, "UNITE THE UNION", date = it) }
        val rules = RecurringAnalyzer.analyze(union)
        assertTrue(NotificationSpend.alreadyAccountedFor(NotificationSpend.Parsed(1686, "UNITE THE UNION", null), union, rules))
        assertFalse(NotificationSpend.alreadyAccountedFor(NotificationSpend.Parsed(425, "GREGGS", null), union, rules))
    }

    // --- which account, when first direct doesn't say ---

    private fun account(id: String, type: AccountType) = AccountEntity(
        id = id, institutionName = "first direct", label = id, currency = "GBP", balanceMinor = 0, lastSynced = 0, accountType = type,
    )
    private val accounts = listOf(account("personal", AccountType.PERSONAL), account("joint", AccountType.JOINT))
    private val sources = listOf(NotificationSpend.Source(fd, "first direct", listOf("personal", "joint"), "personal"))

    @Test
    fun `the payee's usual account is the guess`() {
        val history = listOf(tx("joint", -8_000, "TESCO STORES"), tx("joint", -6_000, "TESCO STORES"), tx("personal", -3_000, "TESCO STORES"))
        val placed = NotificationSpend.placementFor(fd, NotificationSpend.Parsed(4_500, "TESCO STORES", null), sources, accounts, history)!!
        assertEquals("joint", placed.accountId)
        assertTrue(placed.guessed)
        // Never paid before: the starred default, still a guess.
        val fresh = NotificationSpend.placementFor(fd, NotificationSpend.Parsed(4_500, "NEW SHOP", null), sources, accounts, history)!!
        assertEquals(NotificationSpend.Placement("personal", guessed = true), fresh)
    }

    @Test
    fun `when the bank reports a guess on the other account, the hand-over follows the bank`() {
        val at = LocalDateTime.of(2026, 9, 28, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val spend = SeenSpendEntity(
            id = "s", source = fd, seenAt = at, amountMinor = -4_500, currency = "GBP", merchant = "TESCO STORES",
            accountId = "personal", accountGuessed = true,
        )
        val row = tx("joint", -4_500, "TESCO STORES", date = "2026-09-28")
        val m = NotificationSpend.matches(listOf(spend), listOf(row), ZoneOffset.UTC) { setOf("personal", "joint") }
        assertEquals("joint", m["s"]!!.accountId)
        // A certain account is not moved.
        assertTrue(NotificationSpend.matches(listOf(spend.copy(accountGuessed = false)), listOf(row), ZoneOffset.UTC) { setOf("personal", "joint") }.isEmpty())
    }
}
