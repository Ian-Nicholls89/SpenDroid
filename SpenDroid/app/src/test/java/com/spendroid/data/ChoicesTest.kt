package com.spendroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendroid.data.db.BudgetDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 0.6.4: the user's decisions in the database - moved from the old files on the first start,
 * read back as they were written, every type, and kept across a restart. Made-up names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChoicesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, BudgetDb::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun start() = runBlocking {
        Choices.start(context, db.budgetDao())
        Choices.awaitLoaded()
    }

    /** Until the writer has caught up: a write lands in the database a moment after memory. */
    private fun rows(store: String, count: Int) = runBlocking {
        repeat(50) {
            if (db.budgetDao().choices().count { it.store == store } == count) return@runBlocking
            Thread.sleep(20)
        }
        assertEquals(count, db.budgetDao().choices().count { it.store == store })
    }

    @Test
    fun `what the old files held is moved across on the first start, and the files emptied`() {
        context.getSharedPreferences(Stores.CONTRACTS, Context.MODE_PRIVATE).edit()
            .putString("end:gym club", "2027-03-01").putStringSet("accepted", setOf("gym club|2026-03-01")).commit()
        context.getSharedPreferences(Stores.SUBSCRIPTIONS, Context.MODE_PRIVATE).edit()
            .putString("kind:OUT|GBP|999|streamer", "SUBSCRIPTION").putBoolean("remind:OUT|GBP|999|streamer", true).commit()
        start()
        assertEquals(LocalDate.of(2027, 3, 1), ContractEnds(context).all()["gym club"])
        assertEquals(setOf("gym club|2026-03-01"), ContractEnds(context).accepted())
        assertTrue(SubscriptionChoices(context).remind("OUT|GBP|999|streamer"))
        assertTrue(context.getSharedPreferences(Stores.CONTRACTS, Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(context.getSharedPreferences(Stores.SUBSCRIPTIONS, Context.MODE_PRIVATE).all.isEmpty())
        rows(Stores.CONTRACTS, 2)
        rows(Stores.SUBSCRIPTIONS, 2)
    }

    @Test
    fun `written, they're kept in the database, and there after a restart`() {
        start()
        PayeeNames.load(context)
        PayeeNames.set("VIS 4471 SQ *KTH", "Coffee by the station")
        ContractEnds(context).set("gym club", LocalDate.of(2027, 3, 1))
        SubscriptionChoices(context).setRemind("OUT|GBP|999|streamer", true)
        rows(Stores.PAYEE_NAMES, 1)
        rows(Stores.CONTRACTS, 1)
        rows(Stores.SUBSCRIPTIONS, 1)
        // A restart: memory gone, read back from the database.
        start()
        PayeeNames.load(context)
        assertEquals("Coffee by the station", PayeeNames.nameFor("VIS 4471 SQ *KTH"))
        assertEquals(LocalDate.of(2027, 3, 1), ContractEnds(context).all()["gym club"])
        assertTrue(SubscriptionChoices(context).remind("OUT|GBP|999|streamer"))
    }

    @Test
    fun `taken away and cleared, they go from the database too`() {
        start()
        ContractEnds(context).set("gym club", LocalDate.of(2027, 3, 1))
        ContractEnds(context).set("phone", LocalDate.of(2027, 6, 1))
        rows(Stores.CONTRACTS, 2)
        ContractEnds(context).set("gym club", null)
        rows(Stores.CONTRACTS, 1)
        Stores.prefs(context, Stores.CONTRACTS).edit().clear().commit()
        rows(Stores.CONTRACTS, 0)
        start()
        assertTrue(ContractEnds(context).all().isEmpty())
    }

    @Test
    fun `every type comes back as it went in`() {
        listOf<Any>("text", true, 7, 7_000_000_000L, 1.5f, setOf("a", "b")).forEach { value ->
            assertEquals(value, Choices.decode(Choices.encode(value)!!))
        }
        assertFalse(Choices.encode(null) != null)
    }
}
