package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.7.1: the three ways the bar disagreed with the money - a bill that came out at a new amount,
 * spending seen in a notification, and a card bill on a fresh start.
 */
class BarMatchesHeadlineTest {

    private val today = LocalDate.of(2026, 10, 1)
    private var seq = 0

    private fun tx(payee: String, amountMinor: Long, date: LocalDate, account: String = "current", pending: Boolean = false, id: String? = null) =
        TransactionEntity(
            accountId = account,
            transactionId = id ?: "t${seq++}",
            bookingDate = date.toString(),
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = pending,
            rawJson = null,
        )

    private val current = AccountEntity("current", "NatWest", "Personal Account", "GBP", 174327, 0L, AccountType.PERSONAL)

    private val salary = (6..9).map { m -> tx("UKHSA", 183689, LocalDate.of(2026, m, 25)) }

    /** PLAN4HEALTH at £30.73 on the 1st for four months. */
    private val plan = (6..9).map { m -> tx("PLAN4HEALTH", -3073, LocalDate.of(2026, m, 1)) }

    private fun snapshot(all: List<TransactionEntity>, model: BudgetModel = BudgetModel.ROLLOVER) =
        BudgetEngine.snapshot(all, RecurringAnalyzer.analyze(all), listOf(current), today.atTime(15, 0), budgetModel = model)

    @Test
    fun `a bill that comes out at a new amount is the bill, not spending`() {
        val all = salary + plan + tx("PLAN4HEALTH", -3208, today, pending = true) + tx("PORTON STORES", -1486, today.minusDays(2))
        val s = snapshot(all)
        assertEquals(0L, s.spentToday)
        assertEquals(1486L, s.spentThisCycle)
        assertFalse(s.upcomingFixed.any { it.rule.payee == "PLAN4HEALTH" })
    }

    @Test
    fun `the same payee at another time is still spending`() {
        // Mid-month, nowhere near the 1st: not the bill.
        val all = salary + plan + tx("PLAN4HEALTH", -3208, LocalDate.of(2026, 9, 27))
        assertEquals(3208L, snapshot(all).spentThisCycle)
    }

    @Test
    fun `a payment far from the usual amount is not taken for the bill`() {
        val all = salary + plan + tx("PLAN4HEALTH", -9000, today)
        val s = snapshot(all)
        assertEquals(9000L, s.spentToday)
        assertTrue(s.upcomingFixed.any { it.rule.payee == "PLAN4HEALTH" })
    }

    @Test
    fun `spending seen in a notification comes off the balance once`() {
        val base = salary + tx("PORTON STORES", -1486, today.minusDays(2))
        val before = snapshot(base)
        val seen = tx("GREGGS", -425, today, pending = true, id = NotificationSpend.SEEN_PREFIX + "1")
        val after = snapshot(base + seen)
        // The headline drops by the purchase, and the bar's whole stays put.
        assertEquals(before.availableToSpend - 425L, after.availableToSpend)
        assertEquals(before.spendableThisCycle, after.spendableThisCycle)
        assertEquals(before.usedThisCycle + 425L, after.usedThisCycle)
    }

    @Test
    fun `on a fresh start the bar shows a card bill the headline takes off`() {
        val card = AccountEntity("card", "Tesco Bank", "Tesco Card", "GBP", -4000, 0L, AccountType.CREDIT_CARD)
        val all = buildList {
            listOf("2026-06-08", "2026-07-08", "2026-08-08").forEach { add(tx("ACME LTD SALARY", 250000, LocalDate.parse(it))) }
            add(tx("TESCO BANK", -6200, LocalDate.of(2026, 9, 5)))
            add(tx("PAYMENT RECEIVED", 6200, LocalDate.of(2026, 9, 5), account = "card"))
            add(tx("SAINSBURYS", -3000, LocalDate.of(2026, 9, 12), account = "card"))
            add(tx("PRET", -2000, LocalDate.of(2026, 9, 14)))
        }
        val s = BudgetEngine.snapshot(
            all,
            RecurringAnalyzer.analyze(all),
            listOf(current, card),
            LocalDateTime.of(2026, 9, 20, 12, 0),
            budgetModel = BudgetModel.FRESH_START,
        )
        assertTrue(s.upcomingFixed.any { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) })
        assertEquals(s.spendableThisCycle - s.availableToSpend, s.usedThisCycle)
        assertTrue(s.usedThisCycle > s.spentThisCycle)
        assertEquals(s.availableToSpend.toFloat() / s.spendableThisCycle, BudgetPace.remainingFraction(s), 0.001f)
    }
}
