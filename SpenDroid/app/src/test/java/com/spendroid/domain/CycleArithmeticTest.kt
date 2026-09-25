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
 * The arithmetic of one cycle: what is subtracted, over which days, and against which rules.
 * Each case here was once wrong in a way that looked plausible on screen.
 */
class CycleArithmeticTest {

    private val today = LocalDate.of(2026, 9, 20)
    private val now: LocalDateTime = today.atTime(12, 0)

    private val account = AccountEntity(
        id = "current",
        institutionName = "First Direct",
        label = "Personal Account",
        currency = "GBP",
        balanceMinor = 150000,
        lastSynced = 0L,
        accountType = AccountType.PERSONAL,
    )

    private fun tx(payee: String, amountMinor: Long, date: LocalDate) = TransactionEntity(
        accountId = "current",
        transactionId = "$payee-$date-$amountMinor",
        bookingDate = date.toString(),
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
    )

    private fun monthly(payee: String, amountMinor: Long, day: Int, months: IntRange = 6..8) =
        months.map { tx(payee, amountMinor, LocalDate.of(2026, it, day)) }

    private fun snapshot(all: List<TransactionEntity>, model: BudgetModel = BudgetModel.FRESH_START) =
        BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(account),
            referenceTime = now,
            budgetModel = model,
        )

    /**
     * Rent is taken out of the budget once, as a commitment. It was also subtracted a second
     * time while it was still to come, so straight after payday - when every commitment is
     * still to come - the figure was short by the whole of them.
     */
    @Test
    fun `a commitment still to come is not subtracted twice`() {
        val all = monthly("ACME LTD SALARY", 250000, 28) +
            monthly("CITY LETTINGS", -100000, 25) +
            tx("TESCO", -5000, LocalDate.of(2026, 9, 10))
        val s = snapshot(all)

        assertTrue("rent is still to come", s.upcomingFixed.any { it.rule.payee == "CITY LETTINGS" })
        assertEquals(100000L, s.fixedMonthlyOutgoings)
        assertEquals(250000L - 100000L - 5000L, s.availableToSpend)
    }

    /** Carrying over answers from the balance, where what is still due genuinely has to come off. */
    @Test
    fun `carrying over still holds back what is due`() {
        val all = monthly("ACME LTD SALARY", 250000, 28) + monthly("CITY LETTINGS", -100000, 25)
        val s = snapshot(all, BudgetModel.ROLLOVER)
        assertEquals(150000L - 100000L, s.availableToSpend)
    }

    @Test
    fun `spent today is today, not yesterday as well`() {
        val all = monthly("ACME LTD SALARY", 250000, 28) +
            tx("TESCO", -1500, today.minusDays(1)) +
            tx("PRET", -450, today)
        assertEquals(450L, snapshot(all).spentToday)
    }

    /** The week's bars and "spent today" come from one pool, so today's bar is today's figure. */
    @Test
    fun `the last seven days end with today's spending`() {
        val all = monthly("ACME LTD SALARY", 250000, 28) +
            monthly("CITY LETTINGS", -100000, 25) +
            tx("TESCO", -1500, today.minusDays(1)) +
            tx("PRET", -450, today) +
            tx("OLD SHOP", -9999, today.minusDays(7))
        val s = snapshot(all)

        assertEquals(7, s.lastSevenDaysMinor.size)
        assertEquals(s.spentToday, s.lastSevenDaysMinor.last())
        assertEquals(1500L, s.lastSevenDaysMinor[5])
        // Seven days ago is outside the window.
        assertEquals(1500L + 450L, s.lastSevenDaysMinor.sum())
    }

    /**
     * Paid weekly, the cycle is a week long, so a week's share of the budget is what it has
     * to last on. Handing the whole monthly figure to seven days read as four times the money.
     */
    @Test
    fun `a weekly wage budgets for a week`() {
        // Fridays, weekly, ending the Friday before today.
        val fridays = generateSequence(LocalDate.of(2026, 7, 3)) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(LocalDate.of(2026, 9, 18)) }
            .toList()
        val all = fridays.map { tx("WAREHOUSE WAGES", 60000, it) } +
            tx("TESCO", -2000, LocalDate.of(2026, 9, 19))
        val s = snapshot(all)

        val monthly = 60000L * 52L / 12L
        assertEquals(monthly, s.variableMonthlyBudget)
        val weekly = monthly * 12L / 52L
        assertEquals(weekly, s.spendableThisCycle)
        assertEquals(weekly - 2000L, s.availableToSpend)
    }

    /**
     * History is kept past the API's 90 days, so a salary from a job that ended, or a
     * subscription that was cancelled, is still detected from its old rows. Counting it as
     * current income or a current commitment is wrong in both directions.
     */
    @Test
    fun `a rule that has stopped no longer counts`() {
        val oldJob = monthly("OLD EMPLOYER LTD", 180000, 15, 1..4)
        val cancelled = monthly("GYM GROUP", -3500, 3, 1..5)
        val all = monthly("ACME LTD SALARY", 250000, 28) + oldJob + cancelled
        val s = snapshot(all)

        assertEquals(250000L, s.averageMonthlyIncome)
        assertEquals(0L, s.fixedMonthlyOutgoings)
        assertFalse(s.upcomingFixed.any { it.rule.payee == "GYM GROUP" })
    }

    @Test
    fun `a manual rule never goes stale`() {
        val manual = RecurringRule(
            key = "${MANUAL_KEY_PREFIX}1",
            payee = "Council tax",
            direction = Direction.OUT,
            amountMinor = -15000,
            currency = "GBP",
            cadence = Cadence.MONTHLY,
            anchorDay = 1,
            lastOccurrence = LocalDate.of(2025, 1, 1),
            occurrences = 1,
            score = 1f,
        )
        val all = monthly("ACME LTD SALARY", 250000, 28)
        val s = BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all) + manual,
            accounts = listOf(account),
            referenceTime = now,
        )
        assertEquals(15000L, s.fixedMonthlyOutgoings)
    }

    private val paydayHistory = listOf(
        tx("UKHSA", 183689, LocalDate.of(2026, 6, 25)),
        tx("UKHSA", 183689, LocalDate.of(2026, 7, 24)),
        tx("UKHSA", 183689, LocalDate.of(2026, 8, 25)),
        tx("TESCO", -5000, LocalDate.of(2026, 9, 10)),
    )

    private fun onPayday(extra: List<TransactionEntity>): BudgetSnapshot {
        val all = paydayHistory + extra
        return BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(account),
            referenceTime = LocalDate.of(2026, 9, 25).atTime(8, 0),
        )
    }

    /**
     * The reported case: payday has come and the salary is still pending. The cycle does not
     * roll over on a pending payment, which can still change - but nor does it skip today's
     * payday for next month's, which made one cycle two months long and read "53% through"
     * on the first day. It is the old cycle's last day, with income due today.
     */
    @Test
    fun `until the salary is confirmed, payday is due rather than skipped`() {
        val pending = tx("UKHSA", 183689, LocalDate.of(2026, 9, 24)).copy(isPending = true)
        val s = onPayday(listOf(pending))

        assertEquals(LocalDate.of(2026, 8, 25), s.cycleStart)
        assertEquals(LocalDate.of(2026, 9, 25), s.nextIncomeDate)
        assertEquals(0, s.daysUntilNextIncome)
        assertEquals(5000L, s.spentThisCycle)
    }

    /** Once it books, the new cycle starts from the day it landed, and runs a month. */
    @Test
    fun `a confirmed salary starts the new cycle`() {
        val booked = tx("UKHSA", 183689, LocalDate.of(2026, 9, 24))
        val s = onPayday(listOf(booked))

        assertEquals(LocalDate.of(2026, 9, 24), s.cycleStart)
        assertTrue(s.nextIncomeDate!!.isBefore(LocalDate.of(2026, 10, 27)))
        assertEquals(0L, s.spentThisCycle)
    }

    /** A pay rise is still payday: a different amount from the same employer turns the cycle. */
    @Test
    fun `a pay rise still counts as the salary arriving`() {
        val raised = tx("UKHSA", 195000, LocalDate.of(2026, 9, 24))
        assertEquals(LocalDate.of(2026, 9, 24), onPayday(listOf(raised)).cycleStart)
    }
}
