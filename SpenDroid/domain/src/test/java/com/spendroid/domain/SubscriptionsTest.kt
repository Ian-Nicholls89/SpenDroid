package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionsTest {

    private val today = LocalDate.of(2026, 10, 11)
    private val accounts = listOf(
        AccountEntity("personal", "Bank", "Personal Account", "GBP", 100_000, 0L, AccountType.PERSONAL),
        AccountEntity("card", "Bank", "Nectar Card", "GBP", -5_000, 0L, AccountType.CREDIT_CARD),
        AccountEntity("pp", "PayPal", "PayPal", "GBP", 0, 0L, AccountType.PAYPAL),
    )
    private var seq = 0
    private fun tx(account: String, date: LocalDate, minor: Long, payee: String, description: String? = null, transfer: Boolean = false) = TransactionEntity(
        accountId = account, transactionId = "t${seq++}", bookingDate = date.toString(), valueDate = null, amountMinor = minor,
        currency = "GBP", payee = payee, description = description, isPending = false, rawJson = null, isInternalTransfer = transfer,
    )
    private fun rule(payee: String, minor: Long, account: String, last: LocalDate, cadence: Cadence = Cadence.MONTHLY, transfer: Boolean = false) = RecurringRule(
        key = "k-$payee-$account", payee = payee, direction = Direction.OUT, amountMinor = minor, currency = "GBP", cadence = cadence,
        anchorDay = last.dayOfMonth, lastOccurrence = last, occurrences = 4, score = 1f, accountIds = setOf(account), internalTransfer = transfer,
    )
    private fun monthly(account: String, payee: String, minor: Long, months: Int, description: String? = null) =
        (months downTo 1).map { tx(account, today.minusMonths(it.toLong()).withDayOfMonth(5), -minor, payee, description) }

    @Test
    fun `streaming is a subscription, council tax and energy are household, wherever they're paid`() {
        val rules = listOf(
            rule("NETFLIX.COM", -1_299, "personal", today.minusDays(6)),
            rule("COUNCIL TAX", -16_400, "personal", today.minusDays(6)),
            rule("OCTOPUS ENERGY", -9_000, "personal", today.minusDays(6)),
        )
        val all = Subscriptions.all(rules, emptyList(), accounts, emptyMap(), today)
        assertEquals(Subscriptions.Kind.SUBSCRIPTION, all.getValue("k-NETFLIX.COM-personal").kind)
        assertEquals(Subscriptions.Kind.HOUSEHOLD, all.getValue("k-COUNCIL TAX-personal").kind)
        assertEquals(Subscriptions.Kind.HOUSEHOLD, all.getValue("k-OCTOPUS ENERGY-personal").kind)
    }

    @Test
    fun `small and steady on a card or through PayPal is a subscription, the same by direct debit isn't`() {
        val txs = monthly("personal", "PHOTO PRINTS CO", 399, 4, description = "via PayPal · PAYPAL *PHOTOPRINTS")
        val rules = listOf(
            rule("PHOTO PRINTS CO", -399, "personal", today.minusDays(6)),
            rule("CARD THING LTD", -599, "card", today.minusDays(6)),
            rule("LOCAL CLUB", -599, "personal", today.minusDays(6)),
        )
        val all = Subscriptions.all(rules, txs, accounts, emptyMap(), today)
        assertEquals(Subscriptions.Kind.SUBSCRIPTION, all.getValue("k-PHOTO PRINTS CO-personal").kind)
        assertEquals("via PayPal", all.getValue("k-PHOTO PRINTS CO-personal").paidFrom)
        assertEquals(Subscriptions.Kind.SUBSCRIPTION, all.getValue("k-CARD THING LTD-card").kind)
        assertEquals("Nectar Card", all.getValue("k-CARD THING LTD-card").paidFrom)
        assertEquals(Subscriptions.Kind.HOUSEHOLD, all.getValue("k-LOCAL CLUB-personal").kind)
    }

    @Test
    fun `the user's word beats the guess`() {
        val r = rule("NETFLIX.COM", -1_299, "personal", today.minusDays(6))
        val all = Subscriptions.all(listOf(r), emptyList(), accounts, mapOf(r.key to Subscriptions.Kind.HOUSEHOLD), today)
        assertEquals(Subscriptions.Kind.HOUSEHOLD, all.getValue(r.key).kind)
        assertFalse(all.getValue(r.key).guessed)
    }

    @Test
    fun `a year's cost, everything paid, and since when`() {
        val txs = monthly("card", "SPOTIFY", 1_199, 10)
        val r = rule("SPOTIFY", -1_199, "card", today.minusDays(6))
        val s = Subscriptions.all(listOf(r), txs, accounts, emptyMap(), today).getValue(r.key)
        assertEquals(14_388L, s.yearlyMinor)
        assertEquals(11_990L, s.paidInAllMinor)
        assertEquals(today.minusMonths(10).withDayOfMonth(5), s.since)
    }

    @Test
    fun `missed two payments is lapsed, with no next date`() {
        val r = rule("MUSIC UNLIMITED", -1_099, "card", today.minusDays(70))
        val s = Subscriptions.all(listOf(r), emptyList(), accounts, emptyMap(), today).getValue(r.key)
        assertTrue(s.lapsed)
        assertNull(s.next)
    }

    @Test
    fun `PayPal's own copy and transfers aren't subscriptions`() {
        val rules = listOf(
            rule("DISNEYPLUS", -499, "pp", today.minusDays(6)),
            rule("TO SAVINGS", -5_000, "personal", today.minusDays(6), transfer = true),
        )
        assertTrue(Subscriptions.all(rules, emptyList(), accounts, emptyMap(), today).isEmpty())
    }

    @Test
    fun `a yearly one due in the next two months is coming up, one further off isn't`() {
        val soon = rule("OFFICE SUITE", -8_000, "card", today.minusYears(1).plusDays(35), Cadence.ANNUAL)
        val later = rule("ANTIVIRUS", -3_999, "card", today.minusYears(1).plusDays(90), Cadence.ANNUAL)
        val all = Subscriptions.all(listOf(soon, later), emptyList(), accounts, emptyMap(), today)
        assertEquals(listOf(soon.key), Subscriptions.yearlyComing(all.values, today).map { it.rule.key })
    }
}
