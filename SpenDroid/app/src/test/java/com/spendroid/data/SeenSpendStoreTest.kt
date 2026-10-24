package com.spendroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetDb
import com.spendroid.domain.NotificationSpend
import com.spendroid.domain.NotificationSpend.GOOGLE_WALLET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.6.1: notification spending as the phone runs it - [SeenSpendStore] on a real (in-memory)
 * database, fed notifications as the bank's app and Wallet word them, some all at once. Made-up
 * shops, amounts and card digits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SeenSpendStoreTest {

    private val natwest = "com.rbs.mobile.android.natwest"
    private lateinit var db: BudgetDb
    private lateinit var store: SeenSpendStore
    private lateinit var secrets: SecretsStore

    private fun account(id: String, label: String, type: AccountType, card: String? = null, wallet: Boolean = false) = AccountEntity(
        id = id, institutionName = "NatWest", label = label, currency = "GBP", balanceMinor = 100_000,
        lastSynced = 0L, accountType = type, cardLastFour = card, walletLinked = wallet,
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BudgetDb::class.java).allowMainThreadQueries().build()
        secrets = SecretsStore(context)
        store = SeenSpendStore(db.budgetDao(), secrets)
        db.budgetDao().upsertAccounts(
            listOf(
                account("cur", "Current", AccountType.PERSONAL, card = "2291"),
                account("nec", "Nectar card", AccountType.CREDIT_CARD, card = "4168", wallet = true),
            ),
        )
        store.saveSpendSources(listOf(NotificationSpend.Source(natwest, "NatWest", listOf("cur", "nec"))))
        store.setSpendReadingOn(true)
        secrets.updateSpendLearned { emptyList() }
    }

    @After
    fun tearDown() = db.close()

    private val now = System.currentTimeMillis()

    private suspend fun counted() = db.budgetDao().seenSpends().filter { !it.dismissed }

    @Test
    fun `Wallet and the bank announcing one payment count it once, on the card`() = runBlocking {
        assertTrue(store.onNotification(GOOGLE_WALLET, "PORTON STORES", "£12.40 with NatWest Nectar Credit Card ••4168", now))
        store.onNotification(natwest, "NatWest", "💳 £12.40 at PORTON STORES on your card ending 4168", now + 900)
        val spends = counted()
        assertEquals(1, spends.size)
        assertEquals(-1240L, spends.single().amountMinor)
        assertEquals("nec", spends.single().accountId)
        val outcomes = db.budgetDao().notificationSamples().map { it.outcome }.toSet()
        assertEquals(setOf(NotificationSpend.Outcome.COUNTED.name, NotificationSpend.Outcome.DUPLICATE.name), outcomes)
    }

    @Test
    fun `three announcements landing at once are still one payment`() = runBlocking {
        listOf(
            Triple(GOOGLE_WALLET, "SALT DELI", "£35.65 with NatWest Nectar Credit Card ••4168"),
            Triple(GOOGLE_WALLET, "SALT DELI", "£35.65 with NatWest Nectar Credit Card ••4168"),
            Triple(natwest, "NatWest", "💳 £35.65 at SALT DELI on your card ending 4168"),
        ).mapIndexed { i, (source, title, text) ->
            async(Dispatchers.Default) { store.onNotification(source, title, text, now + i * 300L) }
        }.awaitAll()
        assertEquals(1, counted().size)
        assertEquals(3, db.budgetDao().notificationSamples().size)
    }

    @Test
    fun `an answer to which account can be changed, and the card's digits are learnt`() = runBlocking {
        // NatWest's alert without the card's digits: it could be either account.
        store.onNotification(natwest, "NatWest", "💳 £4.25 at GREGGS on your card ending 7777", now)
        val id = counted().single().id
        store.assignSeenSpend(id, "cur")
        assertEquals("cur", counted().single().accountId)
        store.assignSeenSpend(id, "nec")
        val spend = counted().single()
        assertEquals("nec", spend.accountId)
        assertFalse(spend.accountGuessed)
    }

    @Test
    fun `with reading off nothing is counted, and back on it counts again`() = runBlocking {
        store.setSpendReadingOn(false)
        assertFalse(store.onNotification(natwest, "NatWest", "💳 £9.99 at CINEMA on your card ending 2291", now))
        assertTrue(counted().isEmpty())
        assertEquals(NotificationSpend.Outcome.PAUSED.name, db.budgetDao().notificationSamples().single().outcome)
        store.setSpendReadingOn(true)
        assertTrue(store.onNotification(natwest, "NatWest", "💳 £9.99 at CINEMA on your card ending 2291", now + 60_000))
        assertEquals("cur", counted().single().accountId)
    }

    @Test
    fun `the apps watched follow the choice, and Wallet while a card is in it`() = runBlocking {
        assertEquals(setOf(natwest, GOOGLE_WALLET), store.watchedPackages())
        store.saveSpendSources(emptyList())
        assertEquals(setOf(GOOGLE_WALLET), store.watchedPackages())
        store.saveSpendSources(listOf(NotificationSpend.Source(natwest, "NatWest", listOf("cur", "nec"))))
        assertEquals(setOf(natwest, GOOGLE_WALLET), store.watchedPackages())
    }

    @Test
    fun `a notification taught as not a payment stops being counted`() = runBlocking {
        store.onNotification(natwest, "NatWest", "💳 £3.00 at CAR PARK on your card ending 2291", now)
        assertEquals(1, counted().size)
        val sample = db.budgetDao().notificationSamples().single()
        assertTrue(store.teachNotification(sample.id, count = false))
        assertTrue(counted().isEmpty())
        // Worded the same, it isn't read as a payment again.
        assertFalse(store.onNotification(natwest, "NatWest", "💳 £3.00 at CAR PARK on your card ending 2291", now + 3_600_000))
        assertTrue(counted().isEmpty())
    }

    @Test
    fun `a sync's matching-up running alongside notifications loses nothing`() = runBlocking {
        (0 until 12).map { i ->
            async(Dispatchers.Default) {
                if (i % 3 == 0) store.reconcileSeenSpends()
                else store.onNotification(natwest, "NatWest", "💳 £${i}.50 at SHOP $i on your card ending 2291", now + i * 60_000L)
            }
        }.awaitAll()
        // Eight different payments, each once.
        assertEquals(8, counted().size)
        assertEquals(8, counted().map { it.amountMinor }.toSet().size)
    }

    @Test
    fun `a question for a payment that could be either account, and none once answered`() = runBlocking {
        store.onNotification(natwest, "NatWest", "💳 £6.10 at BAKERY on your card ending 7777", now)
        val spend = counted().single()
        val question = store.questionFor(natwest, now)
        if (spend.accountId == null) {
            assertEquals(setOf("cur", "nec"), question!!.second.map { it.id }.toSet())
        }
        store.assignSeenSpend(spend.id, "cur", always = true)
        assertNull(store.questionFor(natwest, now))
        assertEquals("cur", store.placeOf(counted().single()))
    }
}
