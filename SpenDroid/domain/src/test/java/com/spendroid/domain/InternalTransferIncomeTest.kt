package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving money from a personal account into a joint one is neither earning nor spending. It
 * was being counted as both: the standing order showed up as a third recurring income, which
 * is how £500 a month of the user's own money became salary.
 */
class InternalTransferIncomeTest {

    private val now = LocalDateTime.of(2026, 9, 23, 12, 0)

    private val accounts = listOf(
        AccountEntity(
            id = "personal",
            institutionName = "NatWest",
            label = "Personal Account",
            currency = "GBP",
            balanceMinor = 100000,
            lastSynced = 0L,
            accountType = AccountType.PERSONAL,
        ),
        AccountEntity(
            id = "joint",
            institutionName = "NatWest",
            label = "Joint Account",
            currency = "GBP",
            balanceMinor = 50000,
            lastSynced = 0L,
            accountType = AccountType.JOINT,
        ),
    )

    private var seq = 0

    private fun tx(
        account: String,
        date: String,
        amountMinor: Long,
        payee: String,
        transfer: Boolean = false,
    ) = TransactionEntity(
        accountId = account,
        transactionId = "tx-${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
        isInternalTransfer = transfer,
    )

    /** Salary in, and £500 a month moved across to cover the joint account's bills. */
    private fun history(transfersFlagged: Boolean) = buildList {
        listOf("2026-07-25", "2026-08-25", "2026-09-25").forEach {
            add(tx("personal", it, 183669, "UKHSA"))
        }
        listOf("2026-07-26", "2026-08-26", "2026-09-26").forEach {
            // The two legs are named differently, because a payee names the counterparty.
            add(tx("personal", it, -50000, "JOINT ACCOUNT BILLS", transfersFlagged))
            add(tx("joint", it, 50000, "BILLS NICHO", transfersFlagged))
        }
    }

    private fun snapshot(transactions: List<TransactionEntity>) = BudgetEngine.snapshot(
        transactions = transactions,
        rules = RecurringAnalyzer.analyze(transactions),
        accounts = accounts,
        referenceTime = now,
    )

    @Test
    fun `a transfer into a joint account is not a third income`() {
        val budget = snapshot(history(transfersFlagged = true))

        assertEquals(listOf("UKHSA"), budget.incomeRules.map { it.payee })
        assertEquals(183669L, budget.averageMonthlyIncome)
    }

    /**
     * But the other leg is a fixed outgoing. This used to say it was not - "the money is still
     * the user's" - and on carry-over the £500 was then reserved nowhere, so the figure stood
     * £500 high all month and dropped the morning it left. Money moved into the joint pot is
     * money this budget can no longer spend: a commitment like any bill. (Decided 28 Sep 2026.)
     */
    @Test
    fun `the other leg is a fixed outgoing`() {
        val budget = snapshot(history(transfersFlagged = true))

        assertEquals(listOf("JOINT ACCOUNT BILLS"), budget.fixedRules.map { it.payee })
        assertEquals(50000L, budget.fixedMonthlyOutgoings)
    }

    /**
     * A joint account is a shared pot, so nothing it receives is income and nothing it
     * spends is this budget's outgoing - which holds whether or not the transfer into it
     * was ever recognised as one. The two fixes are independent on purpose: the transfer
     * rule filter catches transfers between any two accounts, and this catches the shared
     * pot even when the pairing fails.
     */
    @Test
    fun `a shared pot is excluded whether or not the transfer was recognised`() {
        val unflagged = snapshot(history(transfersFlagged = false))
        val flagged = snapshot(history(transfersFlagged = true))

        assertEquals(183669L, unflagged.averageMonthlyIncome)
        assertEquals(183669L, flagged.averageMonthlyIncome)
    }

    /**
     * The transfer rule filter itself, into savings rather than a shared pot. Recognised as a
     * transfer, the money in is not income; the money out is still committed each month,
     * since the budget does not spend from savings. Unrecognised, the savings credit reads
     * as a second income - the reason recognising transfers matters.
     */
    @Test
    fun `a transfer into savings is committed, and its other leg is not income`() {
        val ordinary = accounts.map {
            if (it.id == "joint") it.copy(accountType = AccountType.SAVINGS) else it
        }
        val unflagged = BudgetEngine.snapshot(
            transactions = history(transfersFlagged = false),
            rules = RecurringAnalyzer.analyze(history(transfersFlagged = false)),
            accounts = ordinary,
            referenceTime = now,
        )
        val flagged = BudgetEngine.snapshot(
            transactions = history(transfersFlagged = true),
            rules = RecurringAnalyzer.analyze(history(transfersFlagged = true)),
            accounts = ordinary,
            referenceTime = now,
        )

        assertEquals(233669L, unflagged.averageMonthlyIncome)
        assertEquals(50000L, unflagged.fixedMonthlyOutgoings)
        assertEquals(183669L, flagged.averageMonthlyIncome)
        assertEquals(50000L, flagged.fixedMonthlyOutgoings)
        assertTrue(flagged.averageMonthlyIncome < unflagged.averageMonthlyIncome)
    }
}
