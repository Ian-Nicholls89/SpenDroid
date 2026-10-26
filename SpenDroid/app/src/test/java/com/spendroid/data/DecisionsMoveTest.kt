package com.spendroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendroid.data.db.BudgetDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.6.6: the user's decisions the settings store held - notification reading, bills left out,
 * transfers, the cycle's income, alerts sent - moved into the database on the first start, read
 * back as they were, and gone from the settings store. Made-up names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DecisionsMoveTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, BudgetDb::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun start(store: SecretsStore) = runBlocking {
        Choices.start(
            context, db.budgetDao(),
            imports = mapOf(Stores.DECISIONS to Choices.Import(read = { store.decisionsToMove() }, done = { store.forgetMovedDecisions() })),
        )
        Choices.awaitLoaded()
    }

    @Test
    fun `decisions in the settings store move to the database, and are read as before`() = runBlocking {
        val store = SecretsStore(context)
        store.keepAsBefore("ignored_rules", """["OUT|GBP|300|car park"]""")
        store.keepAsBefore("transfer_groups", """["OUT|GBP|5000|my savings"]""")
        store.keepAsBefore("primary_income_key", "IN|GBP|183689|employer ltd")
        store.keepAsBefore("spend_places", """{"natwest|greggs":"acc-1"}""")
        store.keepAsBefore("spend_reading_on", false)
        store.keepAsBefore("notified_duplicates", """["dup-1"]""")
        store.saveAccent("BLUE") // a setting, not a decision: stays where it is
        start(store)

        assertEquals(setOf("OUT|GBP|300|car park"), store.ignoredRules.first())
        assertEquals(setOf("OUT|GBP|5000|my savings"), store.transferGroups.first())
        assertEquals("IN|GBP|183689|employer ltd", store.primaryIncomeKey.first())
        assertEquals("acc-1", store.spendPlaces.first()["natwest|greggs"])
        assertFalse(store.spendReadingOn.first())
        assertEquals(setOf("dup-1"), store.notifiedDuplicates.first())
        assertEquals("BLUE", store.accent.first())

        // In the database, and gone from the settings store.
        assertTrue(store.decisionsToMove().isEmpty())
        assertEquals(6, db.budgetDao().choices().count { it.store == Stores.DECISIONS })
    }

    @Test
    fun `changed, they're written to the database and seen at once`() = runBlocking {
        val store = SecretsStore(context)
        start(store)
        store.setRuleIgnored("OUT|GBP|1499|streamer", true)
        store.setTransferGroup("OUT|GBP|5000|my savings", true)
        store.setSpendPlace("natwest|bakery", "acc-2")
        store.savePrimaryIncomeKey("IN|GBP|1|employer")
        assertEquals(setOf("OUT|GBP|1499|streamer"), store.ignoredRules.first())
        assertEquals("acc-2", store.spendPlaces.first()["natwest|bakery"])
        Choices.flush()
        assertEquals(4, db.budgetDao().choices().count { it.store == Stores.DECISIONS })
        store.setRuleIgnored("OUT|GBP|1499|streamer", false)
        assertTrue(store.ignoredRules.first().isEmpty())
        // Clear all data takes what it did before: bills left out, transfers, the income.
        store.clearAll()
        assertTrue(store.transferGroups.first().isEmpty())
        assertEquals(null, store.primaryIncomeKey.first())
        assertEquals("acc-2", store.spendPlaces.first()["natwest|bakery"])
    }
}
