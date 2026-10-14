package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractsTest {

    private val today = LocalDate.of(2026, 10, 13)
    private var seq = 0
    private fun tx(date: LocalDate, minor: Long, payee: String = "PHONE CO") = TransactionEntity(
        accountId = "personal", transactionId = "t${seq++}", bookingDate = date.toString(), valueDate = null, amountMinor = -minor,
        currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )
    private fun rule(minor: Long, last: LocalDate, key: String = "k-phone", payee: String = "PHONE CO") = RecurringRule(
        key = key, payee = payee, direction = Direction.OUT, amountMinor = -minor, currency = "GBP", cadence = Cadence.MONTHLY,
        anchorDay = last.dayOfMonth, lastOccurrence = last, occurrences = 6, score = 1f, accountIds = setOf("personal"),
    )
    private val key = Contracts.keyFor("PHONE CO")

    @Test
    fun `running, then in its last six weeks`() {
        val r = rule(1_800, today.minusDays(3))
        assertTrue(Contracts.status(r, today.plusDays(60), emptyList(), today) is Contracts.Status.Running)
        assertEquals(23L, (Contracts.status(r, today.plusDays(23), emptyList(), today) as Contracts.Status.EndingSoon).daysLeft)
    }

    @Test
    fun `ended and dearer is a rise, with before, after and since`() {
        val end = LocalDate.of(2026, 9, 1)
        val txs = listOf(tx(LocalDate.of(2026, 7, 20), 1_800), tx(LocalDate.of(2026, 8, 20), 1_800), tx(LocalDate.of(2026, 9, 20), 2_700))
        val s = Contracts.status(rule(2_700, LocalDate.of(2026, 9, 20)), end, txs, today) as Contracts.Status.EndedUp
        assertEquals(1_800L, s.beforeMinor)
        assertEquals(2_700L, s.afterMinor)
        assertEquals(LocalDate.of(2026, 9, 20), s.since)
    }

    @Test
    fun `ended at the same price, or by pennies, isn't a rise`() {
        val end = LocalDate.of(2026, 9, 1)
        val txs = listOf(tx(LocalDate.of(2026, 8, 20), 1_800), tx(LocalDate.of(2026, 9, 20), 1_820))
        assertTrue(Contracts.status(rule(1_820, LocalDate.of(2026, 9, 20)), end, txs, today) is Contracts.Status.Ended)
    }

    @Test
    fun `a rise detected as a new bill is still found, by payee`() {
        val end = LocalDate.of(2026, 9, 1)
        val txs = listOf(tx(LocalDate.of(2026, 8, 20), 1_800), tx(LocalDate.of(2026, 9, 20), 2_700))
        val rules = listOf(rule(1_800, LocalDate.of(2026, 8, 20), key = "old"), rule(2_700, LocalDate.of(2026, 9, 20), key = "new"))
        val all = Contracts.all(rules, txs, mapOf(key to end), emptySet(), today)
        assertTrue(all.getValue(key).status is Contracts.Status.EndedUp)
    }

    @Test
    fun `happy with the price, or switched away, and it's settled`() {
        val end = LocalDate.of(2026, 7, 1)
        val txs = listOf(tx(LocalDate.of(2026, 6, 20), 1_800), tx(LocalDate.of(2026, 7, 20), 2_700))
        val stillPaying = rule(2_700, LocalDate.of(2026, 9, 20))
        assertTrue(Contracts.all(listOf(stillPaying), txs, mapOf(key to end), setOf(Contracts.acceptedKey(key, end)), today).getValue(key).status is Contracts.Status.Ended)
        val stopped = rule(2_700, LocalDate.of(2026, 7, 20))
        assertTrue(Contracts.all(listOf(stopped), txs, mapOf(key to end), emptySet(), today).getValue(key).status is Contracts.Status.Ended)
    }

    @Test
    fun `guesses run 12, 18 and 24 months from the first payment`() {
        val txs = listOf(tx(LocalDate.of(2025, 3, 14), 3_800), tx(LocalDate.of(2026, 9, 14), 3_800))
        val g = Contracts.guesses(rule(3_800, LocalDate.of(2026, 9, 14)), txs, today)
        assertEquals(listOf(24 to LocalDate.of(2027, 3, 14)), g.filter { it.first == 24 })
        // Already past - offered too, for a contract that has ended.
        assertEquals(listOf(12 to LocalDate.of(2026, 3, 14)), g.filter { it.first == 12 })
    }
}
