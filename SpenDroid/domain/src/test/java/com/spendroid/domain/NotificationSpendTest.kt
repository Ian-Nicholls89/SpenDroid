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
    fun `a settled row from the day before is another purchase - the same lunch twice in a week`() {
        // Monday's £9.38 lunch, posted Tuesday; the same lunch again Wednesday lunchtime.
        val wednesday = seen("wed", 938, at(23, 12, 41))
        val tuesdayRow = bank("tue", 938, "2026-09-22")
        assertTrue(NotificationSpend.matches(listOf(wednesday), listOf(tuesdayRow), zone).isEmpty())
        // Its own row, when it comes, takes over.
        assertEquals("thu", NotificationSpend.matches(listOf(wednesday), listOf(tuesdayRow, bank("thu", 938, "2026-09-24")), zone)["wed"]!!.transactionId)
        // Already paired with it, as before: unpaired, to be counted again.
        assertEquals(listOf("wed"), NotificationSpend.misdated(listOf(wednesday.copy(matchedTransactionId = "tue")), listOf(tuesdayRow), zone).map { it.id })
    }

    @Test
    fun `the day before still reports a purchase seen in the small hours, or announced late while pending`() {
        // Bought at 23:55, announced at 00:10: the bank dates it the day before.
        assertEquals("t", NotificationSpend.matches(listOf(seen("a", 320, at(23, 0, 10))), listOf(bank("t", 320, "2026-09-22")), zone)["a"]!!.transactionId)
        // A bank announcing last night's purchase at 08:00, its row still pending.
        val late = seen("b", 320, at(23, 8, 0))
        assertEquals("p", NotificationSpend.matches(listOf(late), listOf(bank("p", 320, "2026-09-22").copy(isPending = true)), zone)["b"]!!.transactionId)
        assertTrue(NotificationSpend.misdated(listOf(late.copy(matchedTransactionId = "p")), listOf(bank("p", 320, "2026-09-22").copy(isPending = true)), zone).isEmpty())
    }

    @Test
    fun `paired with a pending row the bank stopped listing, it counts again - and pairs with the booked row`() {
        // £17.24 at Waterstones: paired with First Direct's pending row at 13:30, which a later
        // sync no longer listed - and the purchase showed nowhere.
        val waterstones = seen("w", 1724, at(23, 13, 19), matched = "pending-1")
        val now = at(23, 17, 0)
        assertEquals(listOf("w"), NotificationSpend.orphaned(listOf(waterstones), listOf(bank("other", 500, "2026-09-23")), now).map { it.id })
        // Still listed, or long since booked: left as it is.
        assertTrue(NotificationSpend.orphaned(listOf(waterstones), listOf(bank("pending-1", 1724, "2026-09-23")), now).isEmpty())
        assertTrue(NotificationSpend.orphaned(listOf(waterstones), emptyList(), at(23, 17, 0) + 6 * 86_400_000L).isEmpty())
        // Unpaired, it pairs again with the bank's booked row when that comes.
        val unpaired = waterstones.copy(matchedTransactionId = null)
        assertEquals("booked-1", NotificationSpend.matches(listOf(unpaired), listOf(bank("booked-1", 1724, "2026-09-24")), zone)["w"]!!.transactionId)
    }

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
        // A manual account: no statement yet, or one not reaching far enough past the day - both
        // stand; a statement covering the day, listing it once - the second is a repeat.
        val none = mapOf<String, java.time.LocalDate?>("current" to null)
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second), emptyMap(), none))
        val short = mapOf<String, java.time.LocalDate?>("current" to java.time.LocalDate.of(2026, 9, 28))
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second), emptyMap(), short))
        val covered = mapOf<String, java.time.LocalDate?>("current" to java.time.LocalDate.of(2026, 10, 2))
        assertEquals(listOf(second to "a"), NotificationSpend.repeatsRuledOut(listOf(first, second), emptyMap(), covered))
        // An hour apart is two visits; another card is another purchase.
        val later = seen("c", 6350, at(26, 17, 0)).copy(cardDigits = "4168")
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, later), evening))
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second.copy(cardDigits = "6627")), evening))
    }

    @Test
    fun `penny asks about the same amount at the same shop twice in a day`() {
        val shop = { id: String, h: Int, m: Int -> seen(id, 10000, at(26, h, m)).copy(merchant = "TESCO STORES", cardDigits = "4168") }
        val first = shop("a", 9, 5)
        val second = shop("b", 9, 6)
        val r = NotificationSpend.possibleRepeats(listOf(first, second), zone).single()
        assertEquals("b", r.spend.id)
        assertEquals("a", r.twin.id)
        // The later one is asked about even hours apart - the same day is enough to ask.
        assertEquals("b", NotificationSpend.possibleRepeats(listOf(first, shop("b", 18, 0)), zone).single().spend.id)
        // The one the bank hasn't listed is the one asked about.
        assertEquals("a", NotificationSpend.possibleRepeats(listOf(first, second.copy(matchedTransactionId = "row")), zone).single().spend.id)
        // Both listed by the bank, answered "both were real", or already dropped: no question.
        val none = emptyList<NotificationSpend.Repeat>()
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first.copy(matchedTransactionId = "r1"), second.copy(matchedTransactionId = "r2")), zone))
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first, second.copy(confirmedSeparate = true)), zone))
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first, second.copy(dismissed = true)), zone))
        // Another day, amount, card or shop: no question.
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first, second.copy(seenAt = at(27, 9, 6))), zone))
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first, second.copy(amountMinor = -9999)), zone))
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first, second.copy(cardDigits = "6627")), zone))
        assertEquals(none, NotificationSpend.possibleRepeats(listOf(first, second.copy(merchant = "COSTA")), zone))
        // A similar name is the same shop: "TESCO STORES 2231" and "TESCO".
        assertEquals(1, NotificationSpend.possibleRepeats(listOf(first, second.copy(merchant = "TESCO")), zone).size)
    }

    @Test
    fun `an answered "both were real" is never dropped as a repeat`() {
        val first = seen("a", 6350, at(26, 15, 47), matched = "row").copy(cardDigits = "4168")
        val second = seen("b", 6350, at(26, 15, 57)).copy(cardDigits = "4168", confirmedSeparate = true)
        assertEquals(emptyList<Any>(), NotificationSpend.repeatsRuledOut(listOf(first, second), mapOf("current" to at(26, 20, 0))))
    }

    @Test
    fun `penny's answer is found from the payment or from the bank's row that took it over`() {
        val kept = seen("a", 6350, at(26, 15, 47), matched = "row")
        val repeat = seen("b", 6350, at(26, 15, 57)).copy(dismissed = true, pairedWithId = "a")
        val row = bank("row", 6350, "2026-09-26")
        assertEquals(listOf("b"), NotificationSpend.answerFor("a", null, listOf(kept, repeat), zone)!!.repeats.map { it.id })
        assertEquals(listOf("b"), NotificationSpend.answerFor(null, row, listOf(kept, repeat), zone)!!.repeats.map { it.id })
        // Said "both were real": the other of the two, to change it back.
        val both = seen("c", 6350, at(26, 15, 57)).copy(confirmedSeparate = true)
        assertEquals("a", NotificationSpend.answerFor("c", null, listOf(kept, both), zone)!!.twin!!.id)
        // Nothing answered: nothing to say.
        assertEquals(null, NotificationSpend.answerFor("a", null, listOf(kept), zone))
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

    /** 0.6.2: NatWest's 💳 is two halves of one character; taught apart, the wording never matched again. */
    @Test
    fun `a wording with an emoji in it, taught, matches itself`() {
        val text = "💳 £3.00 at CAR PARK on your card ending 2291"
        val ignore = NotificationSpend.learn(natwest, "NatWest", text, count = false)!!
        assertNull(NotificationSpend.parse(natwest, "NatWest", text, listOf(ignore)))
        val count = NotificationSpend.learn(natwest, "NatWest", "💳 Paid £7.50 to CORNER SHOP", count = true)!!
        assertEquals(750L, NotificationSpend.parse(natwest, "NatWest", "💳 Paid £7.50 to CORNER SHOP", listOf(count))!!.amountMinor)
    }

    @Test
    fun `a wording taught before the fix is repaired as it's read`() {
        val text = "💳 £3.00 at CAR PARK on your card ending 2291"
        val good = NotificationSpend.learn(natwest, "NatWest", text, count = false)!!.pattern
        // As 0.6.1 and before saved it: the emoji's halves quoted apart.
        val broken = good.replace("\\Q\uD83D\uDCB3\\E", "\\Q\uD83D\\E\\Q\uDCB3\\E")
        assertTrue(broken != good)
        assertEquals(good, NotificationSpend.repairPattern(broken))
    }

    @Test
    fun `an absurd figure in a promotion is not a payment and does not throw`() {
        // Twenty digits of pounds overflowed the sum (NumberFormatException) before 0.7.4.
        NotificationSpend.parse(natwest, "Win big", "You spent £99999999999999999999 at the prize draw")
        val p = NotificationSpend.parse(natwest, "Card payment", "You spent £12, at TESCO STORES")!!
        assertEquals(1_200L, p.amountMinor)
    }
}

