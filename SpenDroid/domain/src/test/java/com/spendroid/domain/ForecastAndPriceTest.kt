package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastAndPriceTest {

    private val today = LocalDate.of(2026, 10, 12)
    private var seq = 0
    private fun tx(account: String, date: LocalDate, minor: Long, payee: String, transfer: Boolean = false) = TransactionEntity(
        accountId = account, transactionId = "t${seq++}", bookingDate = date.toString(), valueDate = null,
        amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
        isInternalTransfer = transfer,
    )
    private fun rule(payee: String, minor: Long, day: Int, account: String, last: LocalDate, transfer: Boolean = false) = RecurringRule(
        key = "k-$payee", payee = payee, direction = if (minor < 0) Direction.OUT else Direction.IN, amountMinor = minor,
        currency = "GBP", cadence = Cadence.MONTHLY, anchorDay = day, lastOccurrence = last, occurrences = 6, score = 1f,
        accountIds = setOf(account), internalTransfer = transfer,
    )

    private val joint = AccountEntity("joint", "Bank", "Joint", "GBP", 50_000, 0L, AccountType.JOINT)
    private val personal = AccountEntity("personal", "Bank", "Personal", "GBP", 120_000, 0L, AccountType.PERSONAL)

    @Test
    fun `bills, pay and transfers land on their days`() {
        val rules = listOf(
            rule("MORTGAGE", -94_000, 22, "joint", LocalDate.of(2026, 9, 22)),
            rule("FROM PERSONAL", 100_000, 28, "joint", LocalDate.of(2026, 9, 28), transfer = true),
        )
        val f = Forecast.forAccount(joint, listOf(joint), rules, emptySet(), null, emptyList(), today, LocalDate.of(2026, 10, 31))!!
        assertEquals(50_000L, f.days.first().balanceMinor)
        assertEquals(-44_000L, f.days.first { it.date == LocalDate.of(2026, 10, 22) }.balanceMinor)
        assertEquals(56_000L, f.days.first { it.date == LocalDate.of(2026, 10, 28) }.balanceMinor)
        assertEquals(LocalDate.of(2026, 10, 22), f.firstBelowZero?.date)
    }

    @Test
    fun `a bill due on a Saturday comes out the Monday after, as the budget has it`() {
        val rules = listOf(rule("MORTGAGE", -94_000, 24, "joint", LocalDate.of(2026, 9, 24)))
        val f = Forecast.forAccount(joint, listOf(joint), rules, emptySet(), null, emptyList(), today, LocalDate.of(2026, 10, 31))!!
        assertEquals(LocalDate.of(2026, 10, 26), f.events.single().date)
    }

    @Test
    fun `a dip below zero is a warning naming the bill, the shortfall and what covers it`() {
        val rules = listOf(
            rule("MORTGAGE", -94_000, 22, "joint", LocalDate.of(2026, 9, 22)),
            rule("FROM PERSONAL", 100_000, 28, "joint", LocalDate.of(2026, 9, 28), transfer = true),
        )
        val w = Forecast.warnings(listOf(joint, personal), rules, emptySet(), null, emptyList(), today, LocalDate.of(2026, 11, 12)).single()
        assertEquals("joint", w.account.id)
        assertEquals(LocalDate.of(2026, 10, 22), w.on)
        assertEquals(44_000L, w.shortMinor)
        assertEquals(44_000L, w.coverMinor)
        assertEquals("MORTGAGE", w.cause?.label)
        assertEquals(LocalDate.of(2026, 10, 28), w.nextIn?.date)
    }

    @Test
    fun `a bill already taken this month isn't taken again`() {
        val rules = listOf(rule("WATER CO", -3_150, 10, "personal", LocalDate.of(2026, 9, 10)))
        val paid = listOf(tx("personal", LocalDate.of(2026, 10, 11), -3_150, "WATER CO"))
        val f = Forecast.forAccount(personal, listOf(personal), rules, emptySet(), null, paid, today, LocalDate.of(2026, 11, 9))!!
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `usual day-to-day spending is taken off each day ahead, regular payments aside`() {
        val txs = (1..60).map { tx("personal", today.minusDays(it.toLong()), -1_000, "SHOP $it") } +
            tx("personal", today.minusDays(5), -50_000, "RENT")
        val rules = listOf(rule("RENT", -50_000, 7, "personal", today.minusDays(5)))
        val daily = Forecast.dailySpend(txs, rules, emptySet(), today)
        assertEquals(1_000L, daily)
    }

    @Test
    fun `a bill's rise is found, with what it was, since when, and a year's difference`() {
        val r = rule("WATER CO", -3_420, 9, "personal", LocalDate.of(2026, 10, 9))
        val txs = listOf(
            tx("personal", LocalDate.of(2026, 3, 9), -2_980, "WATER CO"),
            tx("personal", LocalDate.of(2026, 4, 9), -3_150, "WATER CO"),
            tx("personal", LocalDate.of(2026, 5, 9), -3_150, "WATER CO"),
            tx("personal", LocalDate.of(2026, 9, 9), -3_150, "WATER CO"),
            tx("personal", LocalDate.of(2026, 10, 9), -3_420, "WATER CO"),
        )
        val c = PriceChanges.latest(r, txs, today)!!
        assertEquals(3_150L, c.beforeMinor)
        assertEquals(3_420L, c.afterMinor)
        assertEquals(LocalDate.of(2026, 4, 9), c.since)
        assertEquals(LocalDate.of(2026, 10, 9), c.on)
        assertEquals(3_240L, c.perYearMinor)
        assertTrue(PriceChanges.Threshold.PCT5.passes(c))
        assertTrue(!PriceChanges.Threshold.POUNDS5.passes(c))
    }

    @Test
    fun `a steady bill has no change, and a refund from the same payee isn't one`() {
        val r = rule("PHONE", -2_200, 24, "personal", LocalDate.of(2026, 9, 24))
        val txs = listOf(
            tx("personal", LocalDate.of(2026, 8, 24), -2_200, "PHONE"),
            tx("personal", LocalDate.of(2026, 9, 24), -2_200, "PHONE"),
            tx("personal", LocalDate.of(2026, 9, 30), -300, "PHONE"),
        )
        assertNull(PriceChanges.latest(r, txs, today))
    }

    @Test
    fun `variable bills and card bills aren't price rises unless asked`() {
        val variable = rule("ENERGY", -11_800, 1, "personal", LocalDate.of(2026, 10, 1)).copy(key = "${VARIABLE_KEY_PREFIX}energy")
        val txs = listOf(
            tx("personal", LocalDate.of(2026, 9, 1), -10_700, "ENERGY"),
            tx("personal", LocalDate.of(2026, 10, 1), -11_800, "ENERGY"),
        )
        assertTrue(PriceChanges.all(listOf(variable), txs, today).isEmpty())
        assertNotNull(PriceChanges.all(listOf(variable), txs, today, includeVariable = true)[variable.key])
    }

    @Test
    fun `drifting below zero on everyday spending alone isn't blamed on a bill`() {
        // £100 in the account, £20 a day of spending, and a small bill a few days in.
        val small = AccountEntity("small", "Bank", "Small", "GBP", 10_000, 0L, AccountType.PERSONAL)
        val txs = (1..60).map { tx("small", today.minusDays(it.toLong()), -2_000, "SHOP $it") }
        val rules = listOf(rule("PHONE", -1_000, 14, "small", LocalDate.of(2026, 9, 14)))
        assertTrue(Forecast.warnings(listOf(small), rules, emptySet(), null, txs, today, today.plusDays(31)).isEmpty())
    }
}
