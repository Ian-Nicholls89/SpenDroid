package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.8.0: left this month, account and cards together. Pay less the account's bills is the month;
 * this period's statements (last month's card spending) come out of it, so the account can only
 * spend what they leave, and the rest of the month goes on the cards. Made-up figures.
 */
class MonthBudgetTest {

    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    private val accounts = listOf(
        AccountEntity("current", "NatWest", "Current", "GBP", 180000, 0L, AccountType.PERSONAL),
        AccountEntity("card", "Tesco Bank", "Tesco Card", "GBP", -4000, 0L, AccountType.CREDIT_CARD),
    )

    private var seq = 0
    private fun tx(account: String, date: String, amountMinor: Long, payee: String) = TransactionEntity(
        accountId = account, transactionId = "tx-${seq++}", bookingDate = date, valueDate = null, amountMinor = amountMinor,
        currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )

    private val transactions = buildList {
        listOf("2026-06-28", "2026-07-28", "2026-08-28").forEach { add(tx("current", it, 250000, "ACME LTD SALARY")) }
        // A bill paid by card: a bill, not spending.
        listOf("2026-07-03", "2026-08-03", "2026-09-03").forEach { add(tx("card", it, -1099, "NETFLIX")) }
        // Last month's statement, paid from the account this period.
        add(tx("current", "2026-09-05", -6200, "TESCO BANK"))
        add(tx("card", "2026-09-05", 6200, "PAYMENT RECEIVED"))
        // This month's everyday spending: on the card, and from the account.
        add(tx("card", "2026-09-12", -3000, "SAINSBURYS"))
        add(tx("current", "2026-09-14", -2000, "PRET"))
    }

    private fun snapshot(model: BudgetModel = BudgetModel.FRESH_START, timing: CardTiming = CardTiming.AT_BILL, accounts: List<AccountEntity> = this.accounts, transactions: List<TransactionEntity> = this.transactions) =
        BudgetEngine.snapshot(
            transactions = transactions, rules = RecurringAnalyzer.analyze(transactions), accounts = accounts,
            referenceTime = now, budgetModel = model, cardTiming = timing,
        )

    @Test
    fun `the month is the account's figure plus the statements it pays, less this month's card spending and card bills`() {
        listOf(BudgetModel.FRESH_START, BudgetModel.ROLLOVER).forEach { model ->
            val s = snapshot(model)
            val m = assertNotNull(s.month).let { s.month!! }
            assertEquals("$model on cards", 3000L, m.spentOnCardsMinor)
            assertEquals("$model card bill", 1099L, m.cardBillsMinor)
            assertEquals("$model from account", 2000L, m.spentFromAccountMinor)
            assertTrue("$model the statement it paid", m.statements.any { it.paid && it.amountMinor == 6200L && it.label == "Tesco Card" })
            val accountRaw = m.account.availableToSpend - m.account.shortfallMinor
            val left = accountRaw + m.statementsMinor - 3000L - 1099L
            assertEquals("$model left this month", left.coerceAtLeast(0L), s.availableToSpend)
            // This month's spending, wherever it was paid; the bar agrees with the headline.
            assertEquals("$model spent", 5000L, s.spentThisCycle)
            assertEquals(s.spendableThisCycle - s.availableToSpend, s.usedThisCycle)
            assertTrue(s.cycleSpends.any { it.payee == "SAINSBURYS" })
            assertTrue("a card bill isn't spending", s.cycleSpends.none { it.payee == "NETFLIX" })
        }
    }

    @Test
    fun `the account's own figures are kept as they were`() {
        val s = snapshot(BudgetModel.ROLLOVER)
        val account = s.accountOnly
        assertNull(account.month)
        assertTrue("the account is limited by the statements", account.availableToSpend < s.availableToSpend)
    }

    @Test
    fun `no month without cards, or when card spending counts at the till`() {
        assertNull(snapshot(timing = CardTiming.AT_PURCHASE).month)
        val noCard = transactions.filter { it.accountId == "current" && it.payee != "TESCO BANK" }
        assertNull(snapshot(accounts = accounts.take(1), transactions = noCard).month)
    }

    private fun account(available: Long) = BudgetSnapshot(
        averageMonthlyIncome = 200_000, fixedMonthlyOutgoings = 100_000, variableMonthlyBudget = 100_000,
        nextIncomeDate = LocalDate.of(2026, 11, 25), cycleStart = LocalDate.of(2026, 10, 25), cycleEnd = LocalDate.of(2026, 11, 24),
        spentThisCycle = 0, spentToday = 0, upcomingFixed = emptyList(), availableToSpend = available,
        incomeRules = emptyList(), fixedRules = emptyList(), primaryIncomeRule = null, daysUntilNextIncome = 20,
    )

    private fun month(nextStatements: Long, accountLeft: Long, left: Long) = MonthView(
        account = account(accountLeft), statements = listOf(MonthBill("Card", 80_000, paid = true, due = null)),
        spentFromAccountMinor = 0, spentOnCardsMinor = 0, cardBillsMinor = 0,
        nextStatements = listOf(MonthBill("Card", nextStatements, paid = false, due = null)), nextBudgetMinor = 100_000, leftMinor = left,
    )

    @Test
    fun `next month - covered by its own money and the account, once the rest of this month is spent`() {
        // Month spent, £200 from the account and £800 on the card: next month's £800 fits its £1,000.
        assertEquals(0L, month(nextStatements = 80_000, accountLeft = 0, left = 0).nextShortMinor)
        // Month spent, all £1,000 on the card, £200 kept in the account: next month's £1,000 fits.
        assertEquals(0L, month(nextStatements = 100_000, accountLeft = 20_000, left = 0).nextShortMinor)
        // Day 10: £300 on cards so far, £650 left, £150 from the account - whichever way the £650 goes, covered.
        assertEquals(0L, month(nextStatements = 30_000, accountLeft = 15_000, left = 65_000).nextShortMinor)
        // £700 on next month's statements already (£400 of it before this payday), £650 still to spend,
        // £150 in the account: £200 more than next month's £1,000 can take.
        assertEquals(20_000L, month(nextStatements = 70_000, accountLeft = 15_000, left = 65_000).nextShortMinor)
    }

    @Test
    fun `the account pays at most what's left of the month`() {
        // Cards already past this month's statements: the account could cover more than is left.
        assertEquals(40_000L, month(nextStatements = 0, accountLeft = 60_000, left = 40_000).fromAccountMinor)
        assertEquals(15_000L, month(nextStatements = 0, accountLeft = 15_000, left = 65_000).fromAccountMinor)
        assertEquals(0L, month(nextStatements = 0, accountLeft = -5_000, left = 65_000).fromAccountMinor)
    }

    @Test
    fun `money onto a card is asked about - a refund comes off card spending, anything else doesn't`() {
        val withCredit = transactions + tx("card", "2026-09-16", 1000, "SAINSBURYS")
        val key = "card|${withCredit.last().transactionId}"
        fun month(answers: Map<String, Boolean>) = BudgetEngine.snapshot(
            transactions = withCredit, rules = RecurringAnalyzer.analyze(withCredit), accounts = accounts,
            referenceTime = now, cardCredits = answers,
        )
        // Not answered: asked about, and meanwhile not counted. Paying the card off is never asked.
        val asked = month(emptyMap())
        assertEquals(listOf("SAINSBURYS"), asked.month!!.creditsToAsk.map { it.payee })
        assertEquals(3000L, asked.month!!.spentOnCardsMinor)
        // A refund: £10 less spent on cards, £10 more left this month.
        val refund = month(mapOf(key to true))
        assertTrue(refund.month!!.creditsToAsk.isEmpty())
        assertEquals(2000L, refund.month!!.spentOnCardsMinor)
        assertEquals(1000L, refund.month!!.refundsMinor)
        assertEquals(asked.availableToSpend + 1000L, refund.availableToSpend)
        // Not a refund: not asked again, and nothing changes.
        val notRefund = month(mapOf(key to false))
        assertTrue(notRefund.month!!.creditsToAsk.isEmpty())
        assertEquals(asked.availableToSpend, notRefund.availableToSpend)
    }
}

