package com.spendroid.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        ManualRecurringRuleEntity::class,
        BudgetGoalEntity::class,
        BankHolidayEntity::class,
        RuleOverrideEntity::class,
        CategoryRuleEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class BudgetDb : RoomDatabase() {
    abstract fun budgetDao(): BudgetDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN isInternalTransfer INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE manual_recurring_rules (
                        id TEXT PRIMARY KEY,
                        payee TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        currency TEXT NOT NULL,
                        cadence TEXT NOT NULL,
                        anchorDay INTEGER NOT NULL,
                        startDate TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN accountType TEXT NOT NULL DEFAULT 'PERSONAL'")
                db.execSQL("ALTER TABLE accounts ADD COLUMN linkedCreditCardAccountId TEXT")
            }
        }
        /**
         * Types a PayPal account as one.
         *
         * A sync deliberately never overwrites the stored type, so an account linked before
         * AccountType.PAYPAL existed keeps whatever was detected at the time and can never
         * reach the new type on its own - leaving PayPal enrichment silently switched off
         * for exactly the people who already had PayPal linked.
         *
         * Only the two types PayPal could have been auto-detected as are touched, so a type
         * the user chose deliberately is left alone.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE accounts SET accountType = 'PAYPAL'
                    WHERE accountType IN ('PERSONAL', 'OTHER')
                      AND LOWER(institutionName) LIKE '%paypal%'
                    """.trimIndent(),
                )
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN statementDayOfMonth INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN paymentDayOfMonth INTEGER")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule_overrides ADD COLUMN decemberAnchorDay INTEGER")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE bank_holidays (
                        date TEXT NOT NULL,
                        division TEXT NOT NULL,
                        title TEXT NOT NULL,
                        PRIMARY KEY (date, division)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE rule_overrides (
                        ruleKey TEXT PRIMARY KEY NOT NULL,
                        anchorDay INTEGER,
                        shift TEXT
                    )
                    """.trimIndent(),
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN rawBalancesJson TEXT")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN categoryOverride TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE budget_goals (
                        category TEXT PRIMARY KEY NOT NULL,
                        limitMinor INTEGER NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'GBP'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE category_rules (
                        pattern TEXT PRIMARY KEY NOT NULL,
                        category TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}

@Dao
interface BudgetDao {

    @Query("SELECT * FROM accounts ORDER BY lastSynced DESC")
    suspend fun accounts(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY bookingDate DESC, transactionId DESC")
    suspend fun transactionsFor(accountId: String): List<TransactionEntity>

    /**
     * Every stored transaction, including any whose account row no longer exists. Normal
     * reads go through the accounts table, so those rows are invisible to the app - but they
     * are still history that cannot be re-fetched, so a backup has to include them.
     */
    @Query("SELECT * FROM transactions ORDER BY bookingDate DESC, transactionId DESC")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE accountId = :accountId")
    suspend fun deleteTransactions(accountId: String)

    @Query("UPDATE transactions SET isInternalTransfer = 1 WHERE accountId = :accountId AND transactionId = :transactionId")
    suspend fun updateInternalTransfer(accountId: String, transactionId: String)

    @Query("UPDATE transactions SET isInternalTransfer = :isTransfer WHERE accountId = :accountId AND transactionId = :transactionId")
    suspend fun setInternalTransfer(accountId: String, transactionId: String, isTransfer: Boolean)

    @Query("UPDATE transactions SET categoryOverride = :category WHERE accountId = :accountId AND transactionId = :transactionId")
    suspend fun setCategoryOverride(accountId: String, transactionId: String, category: String?)

    @Query("UPDATE transactions SET isRecurring = 1 WHERE accountId = :accountId AND transactionId = :transactionId")
    suspend fun updateRecurring(accountId: String, transactionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManualRule(rule: ManualRecurringRuleEntity)

    @Query("SELECT * FROM manual_recurring_rules WHERE isActive = 1")
    suspend fun getActiveManualRules(): List<ManualRecurringRuleEntity>

    @Query("SELECT * FROM manual_recurring_rules WHERE isActive = 1 ORDER BY payee")
    fun activeManualRulesFlow(): Flow<List<ManualRecurringRuleEntity>>

    @Query("DELETE FROM manual_recurring_rules WHERE id = :id")
    suspend fun deleteManualRule(id: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    @Query("DELETE FROM manual_recurring_rules")
    suspend fun deleteAllManualRules()

    @Query("SELECT * FROM budget_goals")
    suspend fun budgetGoals(): List<BudgetGoalEntity>

    @Query("SELECT * FROM budget_goals")
    fun budgetGoalsFlow(): Flow<List<BudgetGoalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudgetGoal(goal: BudgetGoalEntity)

    @Query("DELETE FROM budget_goals WHERE category = :category")
    suspend fun deleteBudgetGoal(category: String)

    @Query("DELETE FROM budget_goals")
    suspend fun deleteAllBudgetGoals()

    @Query("SELECT * FROM category_rules ORDER BY length(pattern) DESC")
    suspend fun categoryRules(): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules ORDER BY length(pattern) DESC")
    fun categoryRulesFlow(): Flow<List<CategoryRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryRule(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rules WHERE pattern = :pattern")
    suspend fun deleteCategoryRule(pattern: String)

    @Query("DELETE FROM category_rules")
    suspend fun deleteAllCategoryRules()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBankHolidays(holidays: List<BankHolidayEntity>)

    @Query("SELECT * FROM bank_holidays WHERE division = :division AND date >= :from ORDER BY date")
    suspend fun bankHolidaysFrom(division: String, from: String): List<BankHolidayEntity>

    @Query("SELECT COUNT(*) FROM bank_holidays WHERE division = :division AND date >= :from")
    suspend fun countBankHolidaysFrom(division: String, from: String): Int

    @Query("DELETE FROM bank_holidays WHERE date < :before")
    suspend fun deleteBankHolidaysBefore(before: String)

    @Query("SELECT * FROM rule_overrides")
    suspend fun ruleOverrides(): List<RuleOverrideEntity>

    @Query("SELECT * FROM rule_overrides")
    fun ruleOverridesFlow(): Flow<List<RuleOverrideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuleOverride(override: RuleOverrideEntity)

    @Query("DELETE FROM rule_overrides WHERE ruleKey = :ruleKey")
    suspend fun deleteRuleOverride(ruleKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManualRules(rules: List<ManualRecurringRuleEntity>)

    @Query("UPDATE accounts SET accountType = :accountType, linkedCreditCardAccountId = :linkedCreditCardAccountId WHERE id = :id")
    suspend fun updateAccountType(id: String, accountType: String, linkedCreditCardAccountId: String?)

    suspend fun updateAccount(account: AccountEntity) {
        upsertAccount(account)
    }
}