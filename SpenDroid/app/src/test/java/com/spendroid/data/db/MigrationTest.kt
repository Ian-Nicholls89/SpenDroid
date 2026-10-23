package com.spendroid.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.5.8: database upgrades keep what's there. Each version's tables are saved in app/schemas;
 * every migration from 22 on is run here against the version before and checked against the
 * version after, with data in it. (Versions 1 to 21 weren't saved at the time; 22 was rebuilt
 * from the repository's history.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDb::class.java)

    private val name = "migration-test.db"

    @Test
    fun `22 to 23 keeps every seen spend, and none is taken as two real payments`() {
        helper.createDatabase(name, 22).apply {
            execSQL(
                "INSERT INTO seen_spends (id, source, seenAt, amountMinor, currency, merchant, dismissed, keptByUser, accountGuessed) " +
                    "VALUES ('wallet|1', 'wallet', 1760000000000, -938, 'GBP', 'PORTON STORES', 0, 0, 0)",
            )
            execSQL("INSERT INTO accounts (id, institutionName, label, currency, lastSynced, accountType, walletLinked, manual) VALUES ('a', 'First Direct', 'Current', 'GBP', 0, 'PERSONAL', 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 23, true, BudgetDb.MIGRATION_22_23)
        db.query("SELECT merchant, amountMinor, confirmedSeparate FROM seen_spends").use { c ->
            c.moveToFirst()
            assertEquals("PORTON STORES", c.getString(0))
            assertEquals(-938L, c.getLong(1))
            assertEquals(0, c.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM accounts").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    /** The app opens a database the migrations built exactly as it would a new one: the same tables. */
    @Test
    fun `the app opens a migrated database, and every table reads`() {
        helper.createDatabase(name, 22).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDb::class.java, name)
            .addMigrations(BudgetDb.MIGRATION_22_23)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val dao = db.budgetDao()
            dao.upsertAccount(AccountEntity("a", "First Direct", "Current", "GBP", 100, 0, AccountType.PERSONAL))
            dao.upsertSeenSpend(SeenSpendEntity(id = "s", source = "x", seenAt = 1, amountMinor = -1, currency = "GBP", merchant = "Costa", confirmedSeparate = true))
            assertEquals(1, dao.accounts().size)
            assertEquals(true, dao.seenSpends().single().confirmedSeparate)
            dao.duplicateChecks()
            dao.notificationSamples()
            dao.categoryRules()
            dao.ruleOverrides()
            dao.budgetGoals()
        }
        db.close()
    }
}
