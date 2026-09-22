package com.spendroid.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AccountEntity::class, TransactionEntity::class, ManualRecurringRuleEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class BudgetDb : RoomDatabase() {
    abstract fun budgetDao(): BudgetDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN isInternalTransfer INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE transactions ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
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
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE accounts ADD COLUMN accountType TEXT NOT NULL DEFAULT 'PERSONAL'")
                database.execSQL("ALTER TABLE accounts ADD COLUMN linkedCreditCardAccountId TEXT")
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

    @Query("DELETE FROM transactions WHERE accountId = :accountId")
    suspend fun deleteTransactions(accountId: String)

    @Query("UPDATE transactions SET isInternalTransfer = 1 WHERE accountId = :accountId AND transactionId = :transactionId")
    suspend fun updateInternalTransfer(accountId: String, transactionId: String)

    @Query("UPDATE transactions SET isRecurring = 1 WHERE accountId = :accountId AND transactionId = :transactionId")
    suspend fun updateRecurring(accountId: String, transactionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManualRule(rule: ManualRecurringRuleEntity)

    @Query("SELECT * FROM manual_recurring_rules WHERE isActive = 1")
    suspend fun getActiveManualRules(): List<ManualRecurringRuleEntity>

    @Query("DELETE FROM manual_recurring_rules WHERE id = :id")
    suspend fun deleteManualRule(id: String)

    @Query("UPDATE accounts SET accountType = :accountType, linkedCreditCardAccountId = :linkedCreditCardAccountId WHERE id = :id")
    suspend fun updateAccountType(id: String, accountType: String, linkedCreditCardAccountId: String?)

    suspend fun updateAccount(account: AccountEntity) {
        upsertAccount(account)
    }
}