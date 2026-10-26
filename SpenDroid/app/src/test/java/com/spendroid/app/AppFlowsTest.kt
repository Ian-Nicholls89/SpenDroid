package com.spendroid.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.data.ContractEnds
import com.spendroid.data.PayeeNames
import com.spendroid.data.Stores
import com.spendroid.data.SubscriptionChoices
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.domain.NotificationSpend
import com.spendroid.domain.Subscriptions
import com.spendroid.ui.ImportStatus
import com.spendroid.ui.RootViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * 0.6.0 to 0.6.3.1, as the app runs them: a backup taken and everything cleared and restored, the
 * Clear all data list, an encrypted backup's passphrase, a restore from the welcome screen, and a
 * manual account removed. Through the app's own view model, on its own database. Made-up data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowsTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()
    private val repo get() = app.repository
    private val natwest = "com.rbs.mobile.android.natwest"

    /** Runs the main thread, and gives work off it time, until [done] - or fails after ten seconds. */
    private fun until(what: String, done: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(100)
        }
        assertTrue(what, done())
    }

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** An account, a bill paid from it, a payee named, a contract, a subscription choice, a payment seen, settings. */
    private fun seed(): String = runBlocking {
        prefs(Stores.FIRST_RUN).edit().putBoolean("done", true).commit()
        val account = repo.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
        repo.addManualRule(
            ManualRecurringRuleEntity(
                id = "manual-1", payee = "GYM CLUB", direction = "OUT", amountMinor = 2_500, currency = "GBP",
                cadence = "MONTHLY", anchorDay = 3, startDate = "2026-01-03", accountId = account.id,
            ),
        )
        PayeeNames.load(app)
        PayeeNames.set("VIS 4471 SQ *KTH", "Coffee by the station")
        ContractEnds(app).set("gym club", LocalDate.of(2027, 3, 1))
        SubscriptionChoices(app).setKind("OUT|GBP|2500|gym club", Subscriptions.Kind.HOUSEHOLD)
        repo.saveSpendSources(listOf(NotificationSpend.Source(natwest, "NatWest", listOf(account.id), defaultAccountId = account.id)))
        repo.setSpendReadingOn(true)
        repo.onNotification(natwest, "NatWest", "💳 £4.25 at GREGGS on your card ending 2291", System.currentTimeMillis())
        com.spendroid.data.MoneyWatch(app).decide("gym club", com.spendroid.data.MoneyWatch.Decision.EXPECTED)
        Stores.prefs(app, Stores.PENNY_BILLS).edit().putBoolean("OUT|GBP|300|car park", false).commit()
        Stores.prefs(app, Stores.MANUAL_REMINDERS).edit().putInt("statement_every_days", 14).commit()
        repo.setRuleIgnored("OUT|GBP|300|car park", true)
        repo.saveAccent("BLUE")
        repo.saveNotificationTime("19:30")
        prefs(Stores.PRIVACY).edit().putBoolean("hide_recents", true).commit()
        account.id
    }

    private fun file(name: String) = Uri.fromFile(File(app.cacheDir, name))

    @Test
    fun `a backup taken, everything cleared, and restored, brings it all back`() {
        val accountId = seed()
        val vm = RootViewModel(app)
        until("loaded") { vm.state.value.accounts.isNotEmpty() }

        val backup = file("backup.json")
        vm.exportTo(backup)
        until("exported") { vm.state.value.exportStatus is com.spendroid.ui.ExportStatus.Done }

        vm.clearData()
        // Finished once the screen's state is reset - the last thing Clear all data does.
        until("cleared") { runBlocking { repo.accounts().isEmpty() } && vm.state.value.exportStatus !is com.spendroid.ui.ExportStatus.Done }
        // Clear all data: the user's data goes, this phone's settings stay (0.6.0's list).
        assertNull(PayeeNames.nameFor("VIS 4471 SQ *KTH"))
        assertTrue(ContractEnds(app).all().isEmpty())
        assertTrue(SubscriptionChoices(app).kinds().isEmpty())
        assertTrue(Stores.prefs(app, Stores.PENNY_BILLS).all.isEmpty())
        assertTrue(Stores.prefs(app, Stores.MONEY_WATCH).all.isEmpty())
        assertFalse(prefs(Stores.FIRST_RUN).getBoolean("done", false))
        assertTrue("the phone's own settings stay", prefs(Stores.PRIVACY).getBoolean("hide_recents", false))
        assertTrue(runBlocking { repo.seenSpends.first().isEmpty() })

        vm.importFrom(backup)
        until("restored") { vm.state.value.importStatus is ImportStatus.Done }
        runBlocking {
            assertEquals(listOf("Everyday"), repo.accounts().map { it.label })
            assertEquals(accountId, repo.manualRules.first().single().accountId)
            assertEquals(1, repo.seenSpends.first().size)
            assertEquals("BLUE", repo.accent.first())
            // Notification reading and bills left out, kept in the database since 0.6.6.
            assertEquals(listOf("NatWest"), repo.spendSources.first().map { it.label })
            assertTrue("OUT|GBP|300|car park" in repo.ignoredRules.first())
            assertEquals("19:30", repo.notificationTime.first())
        }
        assertEquals("Coffee by the station", PayeeNames.nameFor("VIS 4471 SQ *KTH"))
        assertEquals(LocalDate.of(2027, 3, 1), ContractEnds(app).all()["gym club"])
        assertEquals(Subscriptions.Kind.HOUSEHOLD, SubscriptionChoices(app).kinds()["OUT|GBP|2500|gym club"])
        // Penny's answers, money watch's decisions and the reminders too (0.6.5).
        assertTrue(Stores.prefs(app, Stores.PENNY_BILLS).contains("OUT|GBP|300|car park"))
        assertEquals(com.spendroid.data.MoneyWatch.Decision.EXPECTED, com.spendroid.data.MoneyWatch(app).decision("gym club"))
        assertEquals(14, Stores.prefs(app, Stores.MANUAL_REMINDERS).getInt("statement_every_days", 0))
    }

    @Test
    fun `an encrypted backup asks for its passphrase, refuses a wrong one, and opens with the right one`() {
        seed()
        val json = runBlocking { repo.exportJson(com.spendroid.data.BackupPrefs.export(app), includeBank = true) }
        val locked = File(app.cacheDir, "nightly.spendroid").apply { writeBytes(com.spendroid.data.BackupCrypto.encrypt(json, "made-up passphrase".toCharArray())) }
        val vm = RootViewModel(app)
        until("loaded") { vm.state.value.accounts.isNotEmpty() }
        vm.clearData()
        until("cleared") { runBlocking { repo.accounts().isEmpty() } }

        vm.importFrom(Uri.fromFile(locked))
        until("asked") { vm.state.value.importStatus is ImportStatus.NeedsPassphrase }
        vm.importFrom(Uri.fromFile(locked), "not it at all".toCharArray())
        until("refused") { (vm.state.value.importStatus as? ImportStatus.NeedsPassphrase)?.wrong == true }
        assertTrue(runBlocking { repo.accounts().isEmpty() })
        vm.importFrom(Uri.fromFile(locked), "made-up passphrase".toCharArray())
        until("restored") { vm.state.value.importStatus is ImportStatus.Done }
        assertEquals(listOf("Everyday"), runBlocking { repo.accounts().map { it.label } })
    }

    @Test
    fun `restored on the welcome screen, setup carries on to notification access and counts as done`() {
        seed()
        val json = runBlocking { repo.exportJson(com.spendroid.data.BackupPrefs.export(app)) }
        val backup = File(app.cacheDir, "moved.json").apply { writeText(json) }
        runBlocking { repo.clearAllData() }
        Stores.clearedWithData.forEach { prefs(it).edit().clear().commit() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.mainClock.autoAdvance = false
            fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            fun settle() = repeat(10) { compose.mainClock.advanceTimeBy(150) }
            repeat(20) { if (!shows("Restore a backup")) settle() }
            assertTrue("the welcome screen", shows("Restore a backup"))
            var vm: RootViewModel? = null
            scenario.onActivity { vm = ViewModelProvider(it, RootViewModel.factory(app))[RootViewModel::class.java] }
            vm!!.importFrom(Uri.fromFile(backup))
            repeat(40) { if (!shows("Stay up to date between statements")) { settle(); Thread.sleep(50) } }
            assertTrue("on to notification access", shows("Stay up to date between statements"))
            assertTrue("setup counts as done", prefs(Stores.FIRST_RUN).getBoolean("done", false))
        }
    }

    @Test
    fun `a manual account removed sets its notification payments aside`() {
        val accountId = seed()
        val vm = RootViewModel(app)
        until("loaded") { vm.state.value.accounts.isNotEmpty() }
        assertEquals(1, runBlocking { repo.seenSpends.first().count { !it.dismissed } })
        vm.removeManualAccount(accountId)
        until("removed") { runBlocking { repo.accounts().isEmpty() } }
        until("set aside") { runBlocking { repo.seenSpends.first().none { !it.dismissed } } }
    }

    @Test
    fun `a manual account's balance check anchors to the bank's figure and counts what's gone`() {
        val accountId = seed()
        val vm = RootViewModel(app)
        until("loaded") { vm.state.value.accounts.isNotEmpty() }
        val check = runBlocking { vm.previewBalanceCheck(accountId, 95_000) }
        assertTrue("a check to show", check != null)
        vm.applyBalanceCheck(accountId, 95_000, null)
        // The bank's £950, plus the £4.25 seen in a notification it already reflects - so it isn't
        // taken off twice - and the £245.75 gone that nothing explains, counted as spending.
        until("anchored") { runBlocking { repo.accounts().single().anchorMinor } == 95_425L }
        fun unseen() = runBlocking { repo.storedTransactions(accountId) }.filter { it.payee == com.spendroid.domain.ManualAccounts.NOT_SEEN }
        until("what's gone, counted") { unseen().isNotEmpty() }
        assertEquals(listOf(-24_575L), unseen().map { it.amountMinor })
    }
}
