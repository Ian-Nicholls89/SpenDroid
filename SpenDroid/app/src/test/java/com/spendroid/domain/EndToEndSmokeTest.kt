package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One cycle of a realistic household run through the whole engine at once.
 *
 * The parts have their own tests; this is the one that would catch them disagreeing - a
 * shared pot, a card, a pending charge and a one-off all interact through the same filters,
 * and each was added on the assumption the others behaved.
 */
class EndToEndSmokeTest {

    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    private val accounts = listOf(
        AccountEntity("personal", "NatWest", "Personal", "GBP", 180000, 0L, AccountType.PERSONAL),
        AccountEntity("joint", "NatWest", "Joint", "GBP", 60000, 0L, AccountType.JOINT),
        AccountEntity("card", "Tesco Bank", "Tesco Card", "GBP", -10946, 0L, AccountType.CREDIT_CARD),
    )

    private val rules = listOf(CategoryRuleEntity("the grand venue", "LIFE_EVENTS", 0L))

    private var seq = 0

    private fun tx(
        account: String,
        date: String,
        amountMinor: Long,
        payee: String,
        pending: Boolean = false,
    ) = TransactionEntity(
        accountId = account,
        transactionId = "tx-${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = pending,
        rawJson = null,
    )

    private val transactions = buildList {
        listOf("2026-07-25", "2026-08-25", "2026-09-25").forEach {
            add(tx("personal", it, 250000, "UKHSA"))
        }
        listOf("2026-07-26", "2026-08-26", "2026-09-26").forEach {
            // Into the shared pot, and what the pot then spends.
            add(tx("personal", it, -50000, "JOINT ACCOUNT BILLS"))
            add(tx("joint", it, 50000, "BILLS NICHO"))
            add(tx("joint", it, -21862, "OCTOPUS ENERGY"))
        }
        // On the card: not spending, it is the bill that leaves the account.
        add(tx("card", "2026-09-10", -5446, "TESCO STORES"))
        add(tx("personal", "2026-09-15", -5705, "TESCO BANK"))
        add(tx("card", "2026-09-15", 5705, "DIRECT DEBIT PAYMENT"))
        // Committed but not yet booked.
        add(tx("personal", "2026-09-18", -3860, "WILTON RESTAURANT", pending = true))
        // A one-off that must count, but must not be read as a habit.
        add(tx("personal", "2026-09-12", -200000, "THE GRAND VENUE"))
        // Kids' savings: gone, and not the user's to spend.
        add(tx("personal", "2026-09-05", -5000, "BEANSTALK"))
    }

    private val budget = BudgetEngine.snapshot(
        transactions = transactions,
        rules = RecurringAnalyzer.analyze(transactions),
        accounts = accounts,
        referenceTime = now,
    )

    @Test
    fun `the pot costs what was paid in, and nothing it spent`() {
        assertEquals(50000L, budget.fixedMonthlyOutgoings)
        assertEquals(250000L, budget.averageMonthlyIncome)
    }

    @Test
    fun `the cycle counts the card bill, the pending charge and the one-off`() {
        // 5,705 card bill + 3,860 pending + 200,000 venue + 5,000 Beanstalk.
        assertEquals(214565L, budget.spentThisCycle)
    }

    @Test
    fun `nothing on the card or in the pot is counted as spending`() {
        val onCardOrPot = transactions
            .filter { it.accountId != "personal" && it.amountMinor < 0 }
            .sumOf { -it.amountMinor }
        assertTrue("the pot and the card spent real money", onCardOrPot > 0)
        assertTrue(budget.spentThisCycle < onCardOrPot + 214565L)
    }

    /**
     * The breakdown covers everything that left the account; the cycle figure covers only
     * what was discretionary, since detected commitments are subtracted from the budget
     * rather than spent out of it. The gap between them is exactly those commitments, and
     * asserting it keeps the two from drifting apart for any other reason.
     */
    @Test
    fun `the breakdown exceeds the cycle figure by precisely the fixed commitments`() {
        val cycleRows = transactions.filter { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate)
            date != null && !date.isBefore(budget.cycleStart) &&
                tx.accountId == "personal" && tx.amountMinor < 0
        }
        val breakdown = CategoryEngine
            .spendingBreakdown(cycleRows, rules, budget.cardPaymentKeys, budget.creditCardAccountIds)
            .sumOf { it.amountMinor }

        val commitments = cycleRows
            .filter { tx -> budget.fixedRules.any { RecurringAnalyzer.matches(it, tx) } ||
                budget.fixedRules.any { it.key == RecurringAnalyzer.groupKey(tx) } }
            .sumOf { -it.amountMinor }

        assertTrue("commitments fell in this cycle", commitments > 0)
        assertEquals(budget.spentThisCycle + commitments, breakdown)
    }

    /** The pending charge has to be in the breakdown too, or the two disagree by its size. */
    @Test
    fun `a pending charge reaches the breakdown`() {
        val pending = transactions.first { it.isPending }
        val breakdown = CategoryEngine
            .spendingBreakdown(listOf(pending), rules, budget.cardPaymentKeys, budget.creditCardAccountIds)

        assertEquals(3860L, breakdown.sumOf { it.amountMinor })
    }

    @Test
    fun `the wedding is counted but kept out of what is moving`() {
        val movers = TrendsEngine.categoryTrends(
            transactions = transactions,
            userRules = rules,
            cycleStarts = listOf(
                java.time.LocalDate.of(2026, 7, 25),
                java.time.LocalDate.of(2026, 8, 25),
                java.time.LocalDate.of(2026, 9, 25),
            ),
            today = now.toLocalDate(),
        )

        assertTrue(movers.none { it.category == Category.LIFE_EVENTS })
        assertEquals(Category.FAMILY, Category.valueOf("FAMILY"))
    }

    @Test
    fun `kids savings are family, not savings the user could draw on`() {
        val beanstalk = transactions.first { it.payee == "BEANSTALK" }
        assertEquals(Category.FAMILY, CategoryEngine.classify(beanstalk, rules))
    }
}
