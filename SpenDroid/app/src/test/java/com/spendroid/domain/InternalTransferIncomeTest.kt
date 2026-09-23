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

    /** And not an outgoing either - the money is still the user's. */
    @Test
    fun `nor is the other leg a fixed outgoing`() {
        val budget = snapshot(history(transfersFlagged = true))

        assertEquals(emptyList<String>(), budget.fixedRules.map { it.payee })
        assertEquals(0L, budget.fixedMonthlyOutgoings)
    }

    /**
     * Both legs cancelled in the variable budget, which is why this hid for so long: the
     * headline income and fixed figures were both £500 out, and their difference was right.
     */
    @Test
    fun `the reported income is wrong even when the variable budget survives`() {
        val unflagged = snapshot(history(transfersFlagged = false))
        val flagged = snapshot(history(transfersFlagged = true))

        assertEquals(233669L, unflagged.averageMonthlyIncome)
        assertEquals(50000L, unflagged.fixedMonthlyOutgoings)
        assertEquals(flagged.variableMonthlyBudget, unflagged.variableMonthlyBudget)
        assertTrue(flagged.averageMonthlyIncome < unflagged.averageMonthlyIncome)
    }
}
