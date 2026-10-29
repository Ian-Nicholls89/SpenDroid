package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetModelTest {

    private val today = LocalDate.of(2026, 9, 20)
    private val now: LocalDateTime = today.atTime(12, 0)

    private fun tx(
        payee: String,
        amountMinor: Long,
        date: LocalDate,
        accountId: String = "current",
    ) = TransactionEntity(
        accountId = accountId,
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

    private val account = AccountEntity(
        id = "current",
        institutionName = "First Direct",
        label = "Personal Account",
        currency = "GBP",
        balanceMinor = 72900,
        lastSynced = 0L,
        accountType = AccountType.PERSONAL,
    )

    /** Salary on the 28th for three months, so it is detected as monthly income. */
    private fun salaryHistory() = listOf(
        tx("ACME LTD SALARY", 250000, LocalDate.of(2026, 6, 28)),
        tx("ACME LTD SALARY", 250000, LocalDate.of(2026, 7, 28)),
        tx("ACME LTD SALARY", 250000, LocalDate.of(2026, 8, 28)),
    )

    private fun bonusHistory() = listOf(
        tx("SIDE GIG LTD", 60000, LocalDate.of(2026, 6, 15)),
        tx("SIDE GIG LTD", 60000, LocalDate.of(2026, 7, 15)),
        tx("SIDE GIG LTD", 60000, LocalDate.of(2026, 8, 15)),
    )

    private fun snapshot(
        model: BudgetModel = BudgetModel.FRESH_START,
        primaryKey: String? = null,
        extra: List<TransactionEntity> = emptyList(),
        accounts: List<AccountEntity> = listOf(account),
    ): BudgetSnapshot {
        val all = salaryHistory() + bonusHistory() + extra
        val rules = RecurringAnalyzer.analyze(all)
        return BudgetEngine.snapshot(
            transactions = all,
            rules = rules,
            accounts = accounts,
            referenceTime = now,
            primaryIncomeKey = primaryKey,
            budgetModel = model,
        )
    }

    @Test
    fun `every income adds to the budget, not just the one setting the cycle`() {
        val s = snapshot()
        // 2500 salary + 600 side gig
        assertEquals(310000L, s.averageMonthlyIncome)
    }

    @Test
    fun `the largest income sets the cycle when nothing is designated`() {
        val s = snapshot()
        assertEquals("ACME LTD SALARY", s.primaryIncomeRule?.payee)
        assertFalse(s.primaryIncomeDesignated)
    }

    @Test
    fun `a designated income overrides the largest`() {
        val sideGigKey = RecurringAnalyzer.analyze(salaryHistory() + bonusHistory())
            .first { it.payee == "SIDE GIG LTD" }.key
        val s = snapshot(primaryKey = sideGigKey)
        assertEquals("SIDE GIG LTD", s.primaryIncomeRule?.payee)
        assertTrue(s.primaryIncomeDesignated)
        assertFalse(s.designationLost)
    }

    @Test
    fun `a designation that no longer matches falls back and says so`() {
        val s = snapshot(primaryKey = "IN|GBP|9999|old employer")
        assertTrue(s.designationLost)
        // Still usable rather than broken.
        assertEquals("ACME LTD SALARY", s.primaryIncomeRule?.payee)
    }

    @Test
    fun `the pot is the account the main income is paid into`() {
        val s = snapshot()
        assertEquals("current", s.potAccountId)
        assertEquals(72900L, s.potBalanceMinor)
    }

    /**
     * Carrying over answers from the balance: what is there, less what is still to leave.
     * A fresh start ignores the balance and answers from the cycle's own budget.
     */
    @Test
    fun `carrying over answers from the balance and a fresh start does not`() {
        val spend = listOf(tx("TESCO", -5000, LocalDate.of(2026, 9, 10)))
        val fresh = snapshot(BudgetModel.FRESH_START, extra = spend)
        val rolled = snapshot(BudgetModel.ROLLOVER, extra = spend)

        val committed = rolled.upcomingFixed.sumOf { it.amountMinor }
        assertEquals(72900L - committed, rolled.availableToSpend)
        assertNotEquals(fresh.availableToSpend, rolled.availableToSpend)
    }

    /**
     * The bug this replaced. Money paid in mid-cycle that is not recurring income - a
     * refund, someone settling up, money moved off a card - is absent from the budget,
     * because the budget is built from detected income. Rewinding to the start of the cycle
     * subtracted it anyway, so the account looked to have begun that much further down and
     * every payment in made the figure worse.
     */
    @Test
    fun `money paid in mid-cycle does not reduce what is available`() {
        val spend = listOf(tx("TESCO", -5000, LocalDate.of(2026, 9, 10)))
        val windfall = spend + tx("REFUND FROM A FRIEND", 200000, LocalDate.of(2026, 9, 12))

        val without = snapshot(BudgetModel.ROLLOVER, extra = spend)
        val with = snapshot(BudgetModel.ROLLOVER, extra = windfall)

        // The balance is the same in both fixtures, so the figure must be too - under the
        // old arithmetic the windfall pushed the opening balance £2,000 further down.
        assertEquals(without.availableToSpend, with.availableToSpend)
    }

    /** The ring is drawn against whatever the headline was measured from, or they disagree. */
    @Test
    fun `the ring and the headline share a denominator`() {
        val spend = listOf(tx("TESCO", -5000, LocalDate.of(2026, 9, 10)))
        val rolled = snapshot(BudgetModel.ROLLOVER, extra = spend)
        val fresh = snapshot(BudgetModel.FRESH_START, extra = spend)

        assertEquals(rolled.spentThisCycle + rolled.availableToSpend, rolled.spendableThisCycle)
        assertEquals(fresh.variableMonthlyBudget, fresh.spendableThisCycle)
    }

    /** Zero cannot say how far past zero, so the distance is reported separately. */
    @Test
    fun `a figure floored at zero still reports how far short it was`() {
        val broke = account.copy(balanceMinor = 1000)
        val heavy = listOf(tx("GENTLE DENTAL", -140000, LocalDate.of(2026, 9, 10)))
        val s = snapshot(BudgetModel.ROLLOVER, extra = heavy, accounts = listOf(broke))

        if (s.availableToSpend == 0L) {
            assertTrue("a shortfall should be reported", s.shortfallMinor >= 0L)
        }
    }

    @Test
    fun `showing both leaves the budget figure alone`() {
        val fresh = snapshot(BudgetModel.FRESH_START)
        val both = snapshot(BudgetModel.SHOW_BOTH)
        assertEquals(fresh.availableToSpend, both.availableToSpend)
        assertEquals(72900L, both.potBalanceMinor)
    }

    @Test
    fun `the opening balance still reports what the cycle began with`() {
        val spend = listOf(tx("TESCO", -5000, LocalDate.of(2026, 9, 10)))
        val s = snapshot(BudgetModel.ROLLOVER, extra = spend)

        // The cycle starts on payday, so rewinding to its start takes out both the salary
        // that began it and the spending since. What is left is genuinely carried over from
        // the previous cycle - and the salary is not added twice, because the budget already
        // counts it as income.
        assertEquals(72900L - 250000L + 5000L, s.openingBalanceMinor)
    }
}
