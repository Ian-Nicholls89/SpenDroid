package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.2: renewals - when they're due, what's said, and which payments look like one. Made-up names and figures. */
class RenewalsTest {

    private val today = LocalDate.of(2027, 2, 1)
    private var seq = 0
    private fun tx(date: LocalDate, minor: Long, payee: String) = TransactionEntity(
        accountId = "personal", transactionId = "t${seq++}", bookingDate = date.toString(), valueDate = null, amountMinor = -minor,
        currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )
    private val car = Renewals.Renewal(id = "car", name = "Car insurance", ends = LocalDate.of(2027, 3, 14), lastMinor = 45_000)

    @Test
    fun `quiet until its reminder, then due, then ended`() {
        // Reminder six weeks before: Sun 31 Jan.
        assertEquals(LocalDate.of(2027, 1, 31), car.remindOn)
        assertTrue(Renewals.status(car, LocalDate.of(2027, 1, 30)) is Renewals.Status.Later)
        assertEquals(41L, (Renewals.status(car, today) as Renewals.Status.Due).daysLeft)
        assertEquals(2L, (Renewals.status(car, LocalDate.of(2027, 3, 16)) as Renewals.Status.Ended).daysAgo)
    }

    @Test
    fun `two months, four weeks`() {
        assertEquals(LocalDate.of(2027, 1, 14), car.copy(remind = Renewals.Remind.TWO_MONTHS).remindOn)
        assertEquals(LocalDate.of(2027, 2, 14), car.copy(remind = Renewals.Remind.FOUR_WEEKS).remindOn)
    }

    @Test
    fun `sorted, or put off a fortnight, it goes quiet - and comes back after the fortnight`() {
        assertTrue(Renewals.status(car.copy(sorted = true), today) is Renewals.Status.Quiet)
        val snoozed = car.copy(snoozedUntil = today.plusWeeks(2))
        assertTrue(Renewals.status(snoozed, today.plusDays(13)) is Renewals.Status.Quiet)
        assertTrue(Renewals.status(snoozed, today.plusDays(14)) is Renewals.Status.Due)
    }

    @Test
    fun `an insurer's payment around the end looks like it, wherever the cover went`() {
        val paid = tx(LocalDate.of(2027, 3, 2), 46_218, "ADMIRAL INSURANCE")
        val m = Renewals.lookalike(car, listOf(paid), LocalDate.of(2027, 3, 5))!!
        assertEquals(46_218L, m.amountMinor)
        assertEquals(LocalDate.of(2027, 3, 2), m.date)
    }

    @Test
    fun `not a shop, not outside the weeks around it, and not once answered`() {
        val shop = tx(LocalDate.of(2027, 3, 2), 4_500, "CORNER SHOP")
        val early = tx(LocalDate.of(2026, 12, 1), 46_218, "ADMIRAL INSURANCE")
        val late = tx(LocalDate.of(2027, 4, 20), 46_218, "ADMIRAL INSURANCE")
        assertNull(Renewals.lookalike(car, listOf(shop, early, late), LocalDate.of(2027, 5, 1)))
        val paid = tx(LocalDate.of(2027, 3, 2), 46_218, "ADMIRAL INSURANCE")
        assertNull(Renewals.lookalike(car.copy(notIt = setOf(Renewals.txKey(paid))), listOf(paid), LocalDate.of(2027, 3, 5)))
    }

    @Test
    fun `by its own name, or the payee it renewed with before`() {
        val tv = Renewals.Renewal(id = "tv", name = "TV licence", ends = LocalDate.of(2027, 3, 1))
        assertEquals("TV LICENSING", Renewals.lookalike(tv, listOf(tx(LocalDate.of(2027, 2, 20), 17_450, "TV LICENSING")), today.plusMonths(1))?.payee)
        val gadget = Renewals.Renewal(
            id = "g", name = "Phone plan", ends = LocalDate.of(2027, 3, 1),
            renewedBy = listOf(Renewals.Payment("old", LocalDate.of(2026, 2, 25), "MADE-UP MOBILE LTD", 9_900)),
        )
        assertEquals("MADE-UP MOBILE LTD", Renewals.lookalike(gadget, listOf(tx(LocalDate.of(2027, 2, 24), 10_900, "MADE-UP MOBILE LTD")), today.plusMonths(1))?.payee)
    }

    @Test
    fun `the new end is offered a year on from the old end, and from the payment`() {
        assertEquals(
            listOf(LocalDate.of(2028, 3, 14), LocalDate.of(2028, 3, 2)),
            Renewals.nextEnds(car, LocalDate.of(2027, 3, 2)),
        )
        assertEquals(listOf(LocalDate.of(2028, 3, 14)), Renewals.nextEnds(car, LocalDate.of(2027, 3, 14)))
    }

    @Test
    fun `up on the year before`() {
        val r = car.copy(
            renewedBy = listOf(
                Renewals.Payment("a", LocalDate.of(2026, 3, 2), "ADMIRAL INSURANCE", 46_218),
                Renewals.Payment("b", LocalDate.of(2025, 3, 4), "AVIVA", 43_120),
            ),
        )
        assertEquals(3_098L, Renewals.change(r))
    }

    @Test
    fun `Penny says it in amber on Regular - due, or ended with no new date - short enough for the bubble`() {
        val items = Renewals.all(
            listOf(car, car.copy(id = "home", name = "Home insurance", ends = LocalDate.of(2027, 1, 20)), car.copy(id = "tv", name = "TV licence", ends = LocalDate.of(2027, 9, 1))),
            emptyList(),
            today,
        )
        val lines = TabVoice.renewalLines(items)
        assertEquals(
            listOf("Home insurance ended on 20 Jan. When does the new one end?", "Car insurance ends in 5 weeks - time to compare quotes."),
            lines.map { it.text },
        )
        assertTrue(lines.all { it.needsYou && it.target is TabVoice.Target.Renewal })
        assertTrue(lines.all { it.text.length <= 70 })
    }

    @Test
    fun `while a payment's being asked about, the reminder line waits`() {
        val items = Renewals.all(listOf(car), listOf(tx(LocalDate.of(2027, 1, 30), 46_218, "ADMIRAL INSURANCE")), today)
        assertTrue(items.single().match != null)
        assertTrue(TabVoice.renewalLines(items).isEmpty())
    }

    @Test
    fun `a licence isn't something to get quotes for`() {
        val tv = Renewals.Renewal(id = "tv", name = "TV licence", ends = today.plusWeeks(3))
        assertEquals("TV licence ends in 3 weeks. Worth a look before it renews.", TabVoice.renewalLines(Renewals.all(listOf(tv), emptyList(), today)).single().text)
    }

    @Test
    fun `a monthly insurance direct debit carrying on isn't asked about`() {
        // Pet insurance taken monthly: in the car's weeks, but paid a month before too.
        val monthly = (0..4L).map { tx(LocalDate.of(2026, 11, 3).plusMonths(it), 1_850, "MADE-UP PET INSURANCE") }
        assertNull(Renewals.lookalike(car, monthly, LocalDate.of(2027, 3, 20)))
        // A new insurer's first payment still is.
        val first = tx(LocalDate.of(2027, 3, 4), 46_218, "ADMIRAL INSURANCE")
        assertEquals("ADMIRAL INSURANCE", Renewals.lookalike(car, monthly + first, LocalDate.of(2027, 3, 20))?.payee)
    }

    @Test
    fun `two ending together don't both ask about one payment, nor about one already said to renew the other`() {
        val home = Renewals.Renewal(id = "home", name = "Home insurance", ends = LocalDate.of(2027, 3, 20))
        val paid = tx(LocalDate.of(2027, 3, 2), 46_218, "ADMIRAL INSURANCE")
        val items = Renewals.all(listOf(car, home), listOf(paid), LocalDate.of(2027, 3, 5))
        assertEquals(listOf("car"), items.filter { it.match != null }.map { it.renewal.id })

        val renewedCar = car.copy(renewedBy = listOf(Renewals.Payment(Renewals.txKey(paid), paid.bookingDate.let(LocalDate::parse), paid.payee, 46_218)))
        assertTrue(Renewals.all(listOf(renewedCar, home), listOf(paid), LocalDate.of(2027, 3, 5)).all { it.match == null })
    }
}
