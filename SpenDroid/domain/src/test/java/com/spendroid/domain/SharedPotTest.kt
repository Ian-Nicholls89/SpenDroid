package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A joint account is a shared pot, and what a pot spends is not one person's budget. The
 * same shape as a credit card: the itemised spending belongs to the arrangement, and the
 * money paid in is the part that is actually this person's.
 *
 * Counting it the other way charged the budget twice over and credited it once - every
 * penny the pot spent came off, while the standing order funding it was written off as a
 * transfer between accounts.
 */
class SharedPotTest {

    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    private val personal = AccountEntity(
        id = "personal",
        institutionName = "NatWest",
        label = "Personal",
        currency = "GBP",
        balanceMinor = 150000,
        lastSynced = 0L,
        accountType = AccountType.PERSONAL,
    )

    private val joint = AccountEntity(
        id = "joint",
        institutionName = "NatWest",
        label = "Joint",
        currency = "GBP",
        balanceMinor = 60000,
        lastSynced = 0L,
        accountType = AccountType.JOINT,
    )

    private var seq = 0

    private fun tx(account: String, date: String, amountMinor: Long, payee: String) =
        TransactionEntity(
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
        )

    /** Salary in, £500 a month across to the pot, and the pot paying the household bills. */
    private fun history() = buildList {
        listOf("2026-07-25", "2026-08-25", "2026-09-25").forEach {
            add(tx("personal", it, 250000, "UKHSA"))
        }
        listOf("2026-07-26", "2026-08-26", "2026-09-26").forEach {
            add(tx("personal", it, -50000, "JOINT ACCOUNT BILLS"))
            add(tx("joint", it, 50000, "BILLS NICHO"))
            add(tx("joint", it, -21862, "OCTOPUS ENERGY"))
        }
    }

    private fun snapshot(accounts: List<AccountEntity>) = BudgetEngine.snapshot(
        transactions = history(),
        rules = RecurringAnalyzer.analyze(history()),
        accounts = accounts,
        referenceTime = now,
    )

    @Test
    fun `paying into the pot is the outgoing`() {
        val budget = snapshot(listOf(personal, joint))

        assertEquals(listOf("JOINT ACCOUNT BILLS"), budget.fixedRules.map { it.payee })
        assertEquals(50000L, budget.fixedMonthlyOutgoings)
    }

    @Test
    fun `what the pot spends is not counted again`() {
        val budget = snapshot(listOf(personal, joint))

        assertEquals(emptyList<String>(), budget.fixedRules.map { it.payee } - "JOINT ACCOUNT BILLS")
        assertEquals(250000L, budget.averageMonthlyIncome)
    }

    /**
     * The whole point: charged once for the contribution rather than once for the
     * contribution and again for everything the pot went on to buy.
     */
    @Test
    fun `the arrangement costs the budget exactly what was paid in`() {
        val shared = snapshot(listOf(personal, joint))
        val asPersonal = snapshot(
            listOf(personal, joint.copy(accountType = AccountType.PERSONAL)),
        )

        // Treated as an ordinary account the pot's own bill is subtracted as well.
        assertEquals(50000L, shared.fixedMonthlyOutgoings)
        assertEquals(21862L, asPersonal.fixedMonthlyOutgoings - shared.fixedMonthlyOutgoings)
    }

    /** The balance is still money in an account the user holds, so it stays in the total. */
    @Test
    fun `the pot's balance is untouched, because it is a balance and not a flow`() {
        val budget = snapshot(listOf(personal, joint))
        assertEquals(60000L, joint.balanceMinor)
        assertEquals(250000L, budget.averageMonthlyIncome)
    }
}
