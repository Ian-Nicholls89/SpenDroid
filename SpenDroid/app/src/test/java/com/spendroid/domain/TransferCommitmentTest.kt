package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported case: £500 a month into the joint account, marked as money moved between the
 * user's own accounts. On carry-over it was reserved nowhere, so the figure stood £500 high
 * until the morning it left.
 */
class TransferCommitmentTest {

    private var seq = 0
    private fun tx(account: String, date: String, amount: Long, payee: String, transfer: Boolean = false) =
        TransactionEntity(
            accountId = account, transactionId = "t${seq++}", bookingDate = date, valueDate = null,
            amountMinor = amount, currency = "GBP", payee = payee, description = null,
            isPending = false, rawJson = null, isInternalTransfer = transfer,
        )

    private fun account(id: String, type: AccountType) = AccountEntity(
        id = id, institutionName = "first direct", label = id, currency = "GBP",
        balanceMinor = 150_000, lastSynced = 0, accountType = type,
    )

    private val months = listOf("2026-06", "2026-07", "2026-08")
    private val salary = (months + "2026-09").map { tx("personal", "$it-25", 250_000, "ACME LTD") }

    private fun snapshot(destination: AccountType, model: BudgetModel = BudgetModel.ROLLOVER): BudgetSnapshot {
        val moves = months.flatMap {
            listOf(
                tx("personal", "$it-28", -50_000, "JOINT ACCOUNT BILLS", transfer = true),
                tx("other", "$it-28", 50_000, "BILLS NICHO", transfer = true),
            )
        }
        val all = salary + moves
        return BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(account("personal", AccountType.PERSONAL), account("other", destination)),
            referenceTime = LocalDate.of(2026, 9, 26).atTime(12, 0),
            budgetModel = model,
        )
    }

    @Test
    fun `a regular transfer into the joint account is reserved like a bill`() {
        val s = snapshot(AccountType.JOINT)
        assertEquals(50_000L, s.fixedMonthlyOutgoings)
        val due = s.upcomingFixed.single { it.rule.payee == "JOINT ACCOUNT BILLS" }
        assertEquals(LocalDate.of(2026, 9, 28), due.dueDate)
        // Carry-over: the balance, less the £500 still to leave.
        assertEquals(150_000L - 50_000L, s.availableToSpend)
    }

    @Test
    fun `into savings too`() {
        assertEquals(50_000L, snapshot(AccountType.SAVINGS).fixedMonthlyOutgoings)
    }

    @Test
    fun `between two personal accounts it is still only money moving`() {
        val s = snapshot(AccountType.PERSONAL)
        assertEquals(0L, s.fixedMonthlyOutgoings)
        assertTrue(s.upcomingFixed.none { it.rule.payee == "JOINT ACCOUNT BILLS" })
    }
}
