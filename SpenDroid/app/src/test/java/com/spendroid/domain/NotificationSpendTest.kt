package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.NotificationSpend.GOOGLE_WALLET
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationSpendTest {

    private val natwest = "com.rbs.mobile.android.natwest"
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, h: Int = 12, m: Int = 0) =
        LocalDateTime.of(2026, 9, day, h, m).toInstant(zone).toEpochMilli()

    private fun account(id: String, type: AccountType = AccountType.PERSONAL, wallet: Boolean = false, last4: String? = null) =
        AccountEntity(
            id = id, institutionName = "NatWest", label = id, currency = "GBP", balanceMinor = 0,
            lastSynced = 0, accountType = type, walletLinked = wallet, cardLastFour = last4,
        )

    // --- reading ---

    @Test
    fun `wallet puts the merchant in the title`() {
        val p = NotificationSpend.parse(GOOGLE_WALLET, "Costa Coffee", "£3.20 with Visa •••• 1234")!!
        assertEquals(320L, p.amountMinor)
        assertEquals("Costa Coffee", p.merchant)
        assertEquals("1234", p.cardDigits)
    }

    @Test
    fun `a bank's wording is read for the amount and merchant`() {
        val p = NotificationSpend.parse(natwest, "Card payment", "You spent £1,294.20 at Tesco Stores on your card ending 5678.")!!
        assertEquals(129420L, p.amountMinor)
        assertEquals("Tesco Stores", p.merchant)
        assertEquals("5678", p.cardDigits)
    }

    @Test
    fun `money in, refunds, declines and balances are never spends`() {
        listOf(
            "Tom paid you £20.00",
            "Payment received: £2,500.00 from ACME LTD",
            "Your card payment of £12.99 at Netflix was declined",
            "Refund of £24.99 from Amazon",
            "Your balance is £431.02",
        ).forEach { assertNull(it, NotificationSpend.parse(natwest, "NatWest", it)) }
    }

    @Test
    fun `two amounts, other currencies and no spending words are left alone`() {
        assertNull(NotificationSpend.parse(natwest, "Budget", "You've spent £40.00 of your £200.00 card budget"))
        assertNull(NotificationSpend.parse(GOOGLE_WALLET, "Café Paris", "€4.50 with Visa •••• 1234"))
        assertNull(NotificationSpend.parse(natwest, "NatWest", "Save £5.00 on your next shop"))
    }

    // --- which account ---

    private val current = account("current", last4 = "1234")
    private val nectar = account("nectar", AccountType.CREDIT_CARD, wallet = true, last4 = "5678")
    private val sources = listOf(NotificationSpend.Source(natwest, "NatWest", listOf("current", "nectar"), "current"))

    @Test
    fun `card digits decide`() {
        val p = NotificationSpend.Parsed(500, "Shop", "5678")
        assertEquals("nectar", NotificationSpend.accountFor(natwest, p, sources, listOf(current, nectar)))
    }

    @Test
    fun `without digits a bank app falls back to its default`() {
        val p = NotificationSpend.Parsed(500, "Shop", null)
        assertEquals("current", NotificationSpend.accountFor(natwest, p, sources, listOf(current, nectar)))
    }

    @Test
    fun `wallet uses the only account in it, and asks when there are two`() {
        val p = NotificationSpend.Parsed(500, "Shop", null)
        assertEquals("nectar", NotificationSpend.accountFor(GOOGLE_WALLET, p, sources, listOf(current, nectar)))
        val both = listOf(current.copy(walletLinked = true, cardLastFour = null), nectar.copy(cardLastFour = null))
        assertNull(NotificationSpend.accountFor(GOOGLE_WALLET, p, sources, both))
    }

    @Test
    fun `an app nobody mapped is not read`() {
        assertNull(NotificationSpend.accountFor("com.other", NotificationSpend.Parsed(500, "Shop", null), sources, listOf(current)))
    }

    // --- one purchase, two notifications ---

    private fun seen(id: String, amount: Long, time: Long, account: String? = "current", matched: String? = null) =
        SeenSpendEntity(
            id = id, source = GOOGLE_WALLET, seenAt = time, amountMinor = -amount, currency = "GBP",
            merchant = "Costa", accountId = account, matchedTransactionId = matched,
        )

    @Test
    fun `wallet and the bank announcing one purchase is one spend`() {
        val first = seen("a", 320, at(26, 12, 0))
        assertEquals(first, NotificationSpend.duplicateOf(320, at(26, 12, 4), listOf(first)))
        assertNull(NotificationSpend.duplicateOf(320, at(26, 12, 30), listOf(first)))
    }

    // --- handing over to the bank ---

    private fun bank(id: String, amount: Long, date: String) = TransactionEntity(
        accountId = "current", transactionId = id, bookingDate = date, valueDate = null,
        amountMinor = -amount, currency = "GBP", payee = "COSTA", description = null, isPending = false, rawJson = null,
    )

    @Test
    fun `the bank's row takes over, one to one`() {
        val coffees = listOf(seen("a", 320, at(26, 9)), seen("b", 320, at(26, 15)))
        val rows = listOf(bank("t1", 320, "2026-09-27"), bank("t2", 320, "2026-09-28"))
        val m = NotificationSpend.matches(coffees, rows, zone)
        assertEquals("t1", m["a"]!!.transactionId)
        assertEquals("t2", m["b"]!!.transactionId)
    }

    @Test
    fun `a bank row too late or for another amount does not`() {
        val m = NotificationSpend.matches(
            listOf(seen("a", 320, at(20))),
            listOf(bank("late", 320, "2026-09-26"), bank("other", 450, "2026-09-21")),
            zone,
        )
        assertEquals(emptyMap<String, TransactionEntity>(), m)
    }

    @Test
    fun `only known, unmatched, undismissed spends count`() {
        val list = listOf(
            seen("counted", 100, at(26)),
            seen("unknown", 100, at(26), account = null),
            seen("matched", 100, at(26), matched = "t9"),
            seen("dismissed", 100, at(26)).copy(dismissed = true),
        )
        assertEquals(listOf("counted"), NotificationSpend.counted(list).map { it.id })
    }

    /** A week without the bank reporting it, a counted spend is asked about - unless kept. */
    @Test
    fun `after a week an unreported spend is asked about`() {
        val now = at(26)
        val old = seen("old", 320, now - NotificationSpend.REVIEW_AFTER_MS - 1)
        val kept = old.copy(id = "kept", keptByUser = true)
        val fresh = seen("fresh", 320, now - 60_000)
        assertEquals(listOf("old"), NotificationSpend.unconfirmed(listOf(old, kept, fresh), now).map { it.id })
    }

    /** Counted like pending, on the day it was seen, under an id no sync can collide with. */
    @Test
    fun `a seen spend reads as a pending row`() {
        val row = NotificationSpend.asTransaction(seen("a", 320, at(26, 9)).copy(categoryOverride = "EATING_OUT"), "Google Wallet", zone)
        assertEquals("seen:a", row.transactionId)
        assertEquals("2026-09-26", row.bookingDate)
        assertEquals(-320L, row.amountMinor)
        assertEquals(true, row.isPending)
        assertEquals("EATING_OUT", row.categoryOverride)
        assertEquals("Seen · Google Wallet", row.description)
    }
}
