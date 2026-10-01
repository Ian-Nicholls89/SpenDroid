package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodRowsTest {

    private var seq = 0
    private fun tx(account: String, date: String, amount: Long, payee: String, pending: Boolean = false, transfer: Boolean = false) =
        TransactionEntity(
            accountId = account, transactionId = "t${seq++}", bookingDate = date, valueDate = null, amountMinor = amount,
            currency = "GBP", payee = payee, description = null, isPending = pending, rawJson = null, isInternalTransfer = transfer,
        )

    private fun account(id: String, type: AccountType, extra: AccountEntity.() -> AccountEntity = { this }) = AccountEntity(
        id = id, institutionName = "bank", label = id, currency = "GBP", balanceMinor = 50_000, lastSynced = 0, accountType = type,
    ).extra()

    private val personal = account("Personal", AccountType.PERSONAL)
    private val joint = account("Joint", AccountType.JOINT)
    private val nectar = account("Nectar", AccountType.CREDIT_CARD) { copy(statementDayOfMonth = 23, paymentDayOfMonth = 17, balanceMinor = -129_420) }
    private val accounts = listOf(personal, joint, nectar)

    private val history = buildList {
        listOf("2026-06-25", "2026-07-25", "2026-08-25", "2026-09-25").forEach { add(tx("Personal", it, 250_000, "ACME")) }
        // The joint account usually spends about £400 a cycle; £96.20 so far this one.
        listOf("2026-06-27", "2026-07-27", "2026-08-27").forEach { add(tx("Joint", it, -40_000, "TESCO")) }
        add(tx("Joint", "2026-09-27", -9_620, "TESCO"))
        add(tx("Joint", "2026-09-28", -50_000, "MOVED OUT", transfer = true))
        add(tx("Personal", "2026-09-27", -5_374, "SHOP"))
        add(tx("Nectar", "2026-09-10", -129_420, "BIG SHOP"))
        add(tx("Nectar", "2026-09-28", -3_990, "SALT DELI", pending = true))
    }

    private fun rows(ids: List<String>): List<PeriodRows.Row> {
        val s = BudgetEngine.snapshot(
            transactions = history,
            rules = RecurringAnalyzer.analyze(history),
            accounts = accounts,
            referenceTime = LocalDate.of(2026, 9, 28).atTime(14, 0),
        )
        return PeriodRows.rows(ids, s, accounts, history)
    }

    @Test
    fun `rows come in the order chosen, and a missing account is left out`() {
        assertEquals(listOf("Nectar", "Personal"), rows(listOf("Nectar", "gone", "Personal")).map { it.accountId })
    }

    @Test
    fun `the budget's account is measured against the budget`() {
        val row = rows(listOf("Personal")).single()
        assertEquals(PeriodRows.Kind.BUDGET, row.kind)
        assertEquals(PeriodRows.Against.BUDGET, row.against)
        // £53.74 spent, and on a fresh start the Nectar bill due before payday is gone from the
        // budget too: the row shows what the headline has taken off, not spending alone.
        assertEquals(5_374L + 129_420L, row.spentMinor)
        assertEquals(4, row.day)
    }

    @Test
    fun `a card is its statement, pending included`() {
        val row = rows(listOf("Nectar")).single()
        assertEquals(PeriodRows.Kind.STATEMENT, row.kind)
        assertEquals(3_990L, row.spentMinor)
        assertEquals(3_990L, row.pendingMinor)
    }

    @Test
    fun `another account is measured against its usual cycle, transfers left out`() {
        val row = rows(listOf("Joint")).single()
        assertEquals(PeriodRows.Kind.CYCLE, row.kind)
        assertEquals(9_620L, row.spentMinor)
        assertEquals(40_000L, row.againstMinor)
        assertEquals(PeriodRows.Against.USUAL, row.against)
    }

    @Test
    fun `pace compares what has gone with how far through`() {
        assertEquals(BudgetPace.Pace.ON_TRACK, PeriodRows.paceOf(0.30f, 0.30f))
        assertEquals(BudgetPace.Pace.TIGHT, PeriodRows.paceOf(0.40f, 0.30f))
        assertEquals(BudgetPace.Pace.OVER, PeriodRows.paceOf(0.60f, 0.30f))
        assertEquals(BudgetPace.Pace.ON_TRACK, PeriodRows.paceOf(null, 0.3f))
        assertNull(rows(listOf("gone")).firstOrNull())
        assertTrue(rows(emptyList()).isEmpty())
    }
}
