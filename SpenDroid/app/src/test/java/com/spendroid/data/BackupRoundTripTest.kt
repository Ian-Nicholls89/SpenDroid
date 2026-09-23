package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is the only copy of anything older than 90 days, so the round trip is worth
 * asserting rather than assuming.
 */
class BackupRoundTripTest {

    private val account = AccountEntity(
        id = "acc-1",
        institutionName = "Barclays",
        label = "Everyday Current",
        currency = "GBP",
        balanceMinor = 190466,
        lastSynced = 1_700_000_000_000,
        accountType = AccountType.CREDIT_CARD,
        linkedCreditCardAccountId = "acc-2",
    )

    private val transaction = TransactionEntity(
        accountId = "acc-1",
        transactionId = "tx-1",
        bookingDate = "2026-09-22",
        valueDate = null,
        amountMinor = -2460,
        currency = "GBP",
        payee = "TESCO STORES 3241",
        description = null,
        isPending = false,
        rawJson = """{"raw":"kept"}""",
        isInternalTransfer = true,
        isRecurring = true,
    )

    private fun export() = BackupExporter.toJson(
        accounts = listOf(account),
        transactions = listOf(transaction),
        manualRules = listOf(
            ManualRecurringRuleEntity(
                id = "manual-1",
                payee = "Netflix",
                direction = "OUT",
                amountMinor = 1299,
                currency = "GBP",
                cadence = "MONTHLY",
                anchorDay = 26,
                startDate = "2026-01-26",
                isActive = true,
            ),
        ),
        budgetGoals = listOf(BudgetGoalEntity("GROCERIES", 35000, "GBP")),
        categoryRules = listOf(CategoryRuleEntity("sainsburys", "GROCERIES", 1_700_000_000_000)),
        ignoredRules = setOf("OUT|GBP|15|netflix"),
    )

    @Test
    fun `every field survives the round trip`() {
        val restored = BackupImporter.parse(export())

        assertEquals(listOf(account), restored.accounts)
        assertEquals(listOf(transaction), restored.transactions)
        assertEquals(1, restored.manualRules.size)
        assertEquals("Netflix", restored.manualRules.first().payee)
        assertEquals(setOf("OUT|GBP|15|netflix"), restored.ignoredRules)
        assertEquals(35000, restored.budgetGoals.first().limitMinor)
        assertEquals("GROCERIES", restored.categoryRules.first().category)
    }

    @Test
    fun `nulls stay null rather than becoming the string null`() {
        val restored = BackupImporter.parse(export())
        val tx = restored.transactions.first()
        assertNull(tx.valueDate)
        assertNull(tx.description)
        assertEquals("""{"raw":"kept"}""", tx.rawJson)
    }

    @Test
    fun `a backup from a newer format version is refused, not half-read`() {
        val newer = export().replace("\"formatVersion\": 2", "\"formatVersion\": 99")
        val error = runCatching { BackupImporter.parse(newer) }.exceptionOrNull()
        assertTrue(error is BackupImporter.IncompatibleBackup)
    }

    @Test
    fun `an unrelated json file is refused`() {
        val error = runCatching { BackupImporter.parse("""{"hello":"world"}""") }.exceptionOrNull()
        assertTrue(error is BackupImporter.IncompatibleBackup)
    }

    @Test
    fun `a version 1 backup without budgets still restores`() {
        val v1 = export()
            .replace("\"formatVersion\": 2", "\"formatVersion\": 1")
        val restored = BackupImporter.parse(v1)
        assertEquals(1, restored.transactions.size)
    }
}
