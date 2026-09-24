package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.RuleOverrideEntity
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
        rawBalancesJson = """{"balances":[]}""",
        statementDayOfMonth = 12,
        paymentDayOfMonth = 5,
        identity = "iban:GB29NWBK60161331926819",
        spendingCapMinor = 50000,
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
        // Every user decision set, so a field the backup forgets fails the equality below.
        categoryOverride = "EATING_OUT",
        isCardPayment = true,
        transferOverridden = true,
    )

    private val override = RuleOverrideEntity(
        ruleKey = "IN|GBP|2500|acme ltd",
        anchorDay = 25,
        shift = "PREVIOUS_WORKING_DAY",
        decemberAnchorDay = 19,
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
        ruleOverrides = listOf(override),
        primaryIncomeKey = "IN|GBP|2500|acme ltd",
        budgetModel = "ROLLOVER",
        cardTiming = "AT_PURCHASE",
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
        assertEquals(listOf(override), restored.ruleOverrides)
        assertEquals("IN|GBP|2500|acme ltd", restored.primaryIncomeKey)
        assertEquals("ROLLOVER", restored.budgetModel)
        assertEquals("AT_PURCHASE", restored.cardTiming)
    }

    /** Settings missing from an older file are left alone on restore, not cleared. */
    @Test
    fun `a backup without settings restores none`() {
        val v2 = BackupExporter.toJson(
            accounts = listOf(account),
            transactions = listOf(transaction),
            manualRules = emptyList(),
            budgetGoals = emptyList(),
            categoryRules = emptyList(),
            ignoredRules = emptySet(),
        )
        val restored = BackupImporter.parse(v2)
        assertNull(restored.primaryIncomeKey)
        assertNull(restored.budgetModel)
        assertTrue(restored.ruleOverrides.isEmpty())
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
        val newer = export().replace(
            "\"formatVersion\": ${BackupExporter.FORMAT_VERSION}",
            "\"formatVersion\": 99",
        )
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
            .replace("\"formatVersion\": ${BackupExporter.FORMAT_VERSION}", "\"formatVersion\": 1")
        val restored = BackupImporter.parse(v1)
        assertEquals(1, restored.transactions.size)
    }
}
