package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.NotificationSpend.GOOGLE_WALLET
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val wallet = seen("a", 425, at(26, 12, 0)).copy(cardDigits = "4168")
        // NatWest a slow twenty minutes later, same card.
        assertEquals(wallet, NotificationSpend.duplicateOf(natwest, 425, "4168", at(26, 12, 20), listOf(wallet)))
        // No digits on one side is still the same purchase.
        assertEquals(wallet, NotificationSpend.duplicateOf(natwest, 425, null, at(26, 12, 20), listOf(wallet)))
        // Another card is another purchase. The bank may be hours behind Wallet - up to a day.
        assertNull(NotificationSpend.duplicateOf(natwest, 425, "9999", at(26, 12, 20), listOf(wallet)))
        assertEquals(wallet, NotificationSpend.duplicateOf(natwest, 425, null, at(26, 15, 5), listOf(wallet)))
        assertNull(NotificationSpend.duplicateOf(natwest, 425, "4168", at(27, 12, 5), listOf(wallet)))
    }

    @Test
    fun `one app announcing the same amount twice is two purchases, unless it is a repost`() {
        val first = seen("a", 425, at(26, 12, 0)).copy(source = natwest)
        assertNull(NotificationSpend.duplicateOf(natwest, 425, null, at(26, 12, 5), listOf(first)))
        assertEquals(first, NotificationSpend.duplicateOf(natwest, 425, null, at(26, 12, 0) + 30_000, listOf(first)))
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
    fun `a claim from weeks before does not hold a row - a bank reusing ids on pending rows`() {
        val old = seen("old", 6350, at(1), matched = "p1")
        val now = seen("now", 6350, at(26, 16))
        val m = NotificationSpend.matches(listOf(old, now), listOf(bank("p1", 6350, "2026-09-26")), zone)
        assertEquals("p1", m["now"]!!.transactionId)
        // A claim the same week still holds: two shops at one price stay two.
        val twin = seen("twin", 6350, at(25), matched = "p1")
        assertEquals(emptyMap<String, TransactionEntity>(), NotificationSpend.matches(listOf(twin, now), listOf(bank("p1", 6350, "2026-09-26")), zone))
    }

    @Test
    fun `counted on an account the bank does not list it under, the shop's name pairs them`() {
        val wallet = seen("w", 6350, at(26, 16)).copy(merchant = "WAITROSE STORES", accountGuessed = true)
        val row = bank("x", 6350, "2026-09-26").copy(accountId = "card", payee = "WAITROSE STORES 4168", isPending = true)
        assertEquals("x", NotificationSpend.matches(listOf(wallet), listOf(row), zone)["w"]!!.transactionId)
        // Another shop at the same price on another account is not it.
        val other = row.copy(payee = "TESCO STORES 2231")
        assertEquals(emptyMap<String, TransactionEntity>(), NotificationSpend.matches(listOf(wallet), listOf(other), zone))
    }

    @Test
    fun `the matching notes say what the matcher would do, and why each line doesn't fit`() {
        val wallet = seen("w", 6350, at(26, 16)).copy(merchant = "WAITROSE STORES", cardDigits = "4168")
        val other = bank("x", 6350, "2026-09-26").copy(accountId = "card", payee = "WAITROSE STORES", isPending = true)
        val name = { id: String? -> id ?: "none" }
        val lines = NotificationSpend.explain(wallet, listOf(wallet), listOf(other), emptySet(), name, zone)
        assertTrue(lines.toString(), lines.any { it == "Would not pair now" })
        assertTrue(lines.toString(), lines.any { it.startsWith("WAITROSE STORES · card · 2026-09-26 · pending") && it.endsWith("another account") })
        val here = other.copy(accountId = "current")
        val fits = NotificationSpend.explain(wallet, listOf(wallet), listOf(here), emptySet(), name, zone)
        assertTrue(fits.toString(), fits.any { it.startsWith("Would pair now with WAITROSE STORES on current") })
        assertTrue(fits.toString(), fits.any { it.endsWith(": fits") })
    }

    @Test
    fun `wallet announcing one shop twice, the bank listing it once - the second is a repeat`() {
        val first = seen("a", 6350, at(26, 15, 47), matched = "row").copy(cardDigits = "4168")
        val second = seen("b", 6350, at(26, 15, 57)).copy(cardDigits = "4168")
        val evening = mapOf("current" to at(26, 20, 0))
        assertEquals(listOf(second to "a"), NotificationSpend.repeatsRuledOut(listOf(first, second), evening))
        // Before the bank has had time to list a second purchase, both stand.
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second), mapOf("current" to at(26, 16, 30))))
        // No bank to ask - a manual account - both stand.
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second), emptyMap()))
        // An hour apart is two visits; another card is another purchase.
        val later = seen("c", 6350, at(26, 17, 0)).copy(cardDigits = "4168")
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, later), evening))
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second.copy(cardDigits = "6627")), evening))
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
        assertEquals("Seen · Google Wallet · 09:00", row.description)
    }

    /** NatWest's real wording, from the user's phone (27 Sep 2026). */
    @Test
    fun `natwest's card notification`() {
        val p = NotificationSpend.parse(natwest, "NatWest", "💳 £4.25 at GREGGS on your card ending 4168")!!
        assertEquals(425L, p.amountMinor)
        assertEquals("GREGGS", p.merchant)
        assertEquals("4168", p.cardDigits)
    }

    /**
     * The user's lunch (28 Sep 2026): Wallet posted twice and NatWest once, in the same second.
     * Read, each is £35.65 on ••4168; counted, it is one purchase.
     */
    @Test
    fun `one lunch announced three times is one spend`() {
        val wallet = NotificationSpend.parse(GOOGLE_WALLET, "SALT DELI KITCHEN LIMI", "£35.65 with NatWest Nectar Credit Card ••4168")!!
        assertEquals(3565L, wallet.amountMinor)
        assertEquals("SALT DELI KITCHEN LIMI", wallet.merchant)
        assertEquals("4168", wallet.cardDigits)
        val bank = NotificationSpend.parse(natwest, "NatWest", "💳 £35.65 at SALT DELI KITCHEN LIMI on your card ending 4168")!!
        assertEquals(3565L, bank.amountMinor)

        val t = at(28, 12, 33)
        val three = listOf(
            seen("w1", 3565, t).copy(cardDigits = "4168"),
            seen("w2", 3565, t + 400).copy(cardDigits = "4168"),
            seen("n1", 3565, t + 900).copy(source = natwest, cardDigits = "4168"),
        )
        assertEquals(listOf("w2", "n1"), NotificationSpend.repeats(three).map { it.id })
        // A second lunch later, paid with the plastic card - NatWest tells of it, Wallet doesn't:
        // the first lunch's Wallet announcement already has its partner, so it stays two.
        val second = three[2].copy(id = "n2", seenAt = t + 4 * 3_600_000)
        assertEquals(listOf("w2", "n1"), NotificationSpend.repeats(three + second).map { it.id })
    }
}
