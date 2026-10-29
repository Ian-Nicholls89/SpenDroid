package com.spendroid.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.data.db.AccountType
import com.spendroid.domain.NotificationSpend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.6.3: the whole app, started as on the phone - its own application, database and view model -
 * and gone round as a user would: Penny's question as it opens, every tab, the side menu's
 * screens, and a bill added by hand. Made-up account and payment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppRunTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val natwest = "com.rbs.mobile.android.natwest"

    private fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BudgetApplication>()
        app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        val repo = app.repository
        val account = repo.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
        repo.saveSpendSources(listOf(NotificationSpend.Source(natwest, "NatWest", listOf(account.id), defaultAccountId = account.id)))
        repo.setSpendReadingOn(true)
        // 5p: a card check, so Penny has a question as the app opens.
        repo.onNotification(natwest, "NatWest", "💳 £0.05 at CAR PARK on your card ending 2291", System.currentTimeMillis())
    }

    /** The bar at the foot's tab: the lowest thing on screen that can be tapped and says [label]. */
    private fun tab(label: String) {
        val matcher = hasText(label) and hasClickAction()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue("no tab $label", nodes.isNotEmpty())
        val lowest = nodes.indices.maxBy { nodes[it].boundsInRoot.top }
        compose.onAllNodes(matcher)[lowest].performClick()
        settle()
    }

    /** Taps the one tappable thing labelled exactly [label] - a menu item, not a card that also says it. */
    private fun tapExactly(label: String) {
        val matcher = hasText(label) and hasClickAction()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        val only = nodes.indices.filter { i ->
            (nodes[i].config.find { it.key == androidx.compose.ui.semantics.SemanticsProperties.Text }?.value as? List<*>)?.map { it.toString() } == listOf(label)
        }
        assertTrue("one $label: ${only.size}", only.size == 1)
        compose.onAllNodes(matcher)[only.single()].performClick()
        settle()
    }

    /**
     * A second and a half of the app's time. Penny never stops moving - her idle life, her lines
     * looping on Home - so waiting until nothing moves would wait for ever: time is moved on
     * instead, enough for any screen change, sheet or dialog to finish.
     */
    private fun settle() {
        repeat(10) { compose.mainClock.advanceTimeBy(150) }
    }

    /** Moves the app's time on until [what] is on screen, or fails after twenty tries (half a minute). */
    private fun until(what: String, condition: () -> Boolean) {
        repeat(20) {
            if (condition()) return
            settle()
        }
        assertTrue(what, condition())
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `round the app as a user would`() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()

            // Penny's question, as the app opens; put off for later.
            until("Penny's sheet") { shows("PENNY HAS A QUESTION") }
            compose.onNodeWithText("Ask me later").performClick()
            settle()
            until("sheet gone") { !shows("PENNY HAS A QUESTION") }

            // Every tab.
            tab("Spending")
            until("Spending") { shows("Spending") }
            tab("Accounts")
            until("the account") { shows("ACCOUNTS WITHOUT A BANK LINK") }
            tab("Regular")
            until("Regular") { shows("Add manual rule") || shows("Add a regular payment by hand") || compose.onAllNodes(hasContentDescription("Add a regular payment by hand")).fetchSemanticsNodes().isNotEmpty() }
            // With no regular payments yet, the empty page's own button; otherwise the round one.
            if (shows("Add manual rule")) compose.onNodeWithText("Add manual rule").performClick()
            else compose.onNodeWithContentDescription("Add a regular payment by hand").performClick()
            settle()
            until("add a bill") { shows("Add recurring rule") }
            compose.onNodeWithText("Cancel").performClick()
            settle()
            until("dialog gone") { !shows("Add recurring rule") }
            tab("Settings")
            until("settings list") { shows("YOUR BUDGET") }
            tab("Home")
            until("Home") { !shows("YOUR BUDGET") }

            // The side menu's screens.
            compose.onNodeWithContentDescription("Menu").performClick()
            settle()
            tapExactly("Today")
            until("today") { shows("to payday") || shows("Nothing to show yet.") }
            compose.onNode(hasContentDescription("Back")).performClick()
            until("today closed") { !shows("to payday") && !shows("Nothing to show yet.") }
            compose.onNodeWithContentDescription("Menu").performClick()
            settle()
            tapExactly("Last cycle")
            until("review") { shows("look back on yet") || shows("Adding up the last cycle") || shows("Each day") }
            compose.onNode(hasContentDescription("Back")).performClick()
            until("review closed") { !shows("look back on yet") && !shows("Adding up the last cycle") && !shows("Each day") }

            // Penny's question, put off, is still there to come back to: the strip on Home.
            until("the strip") { shows("question from Penny") }
        }
    }

    /**
     * 0.7.0: Penny talking on a tab - Accounts, about a statement not imported for six weeks - with
     * its link, and not in the banner there as well; on Settings, where she says nothing, she is.
     */
    @Test
    fun `Penny talks on a tab, and leaves the banner`() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<BudgetApplication>()
            app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
            val account = app.repository.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
            val day = java.time.LocalDate.now().minusDays(42)
            app.repository.addImported(listOf(com.spendroid.data.db.TransactionEntity(
                accountId = account.id, transactionId = com.spendroid.domain.DuplicateCheck.IMPORTED_PREFIX + "made-up-1", bookingDate = day.toString(), valueDate = null,
                amountMinor = -1_250, currency = "GBP", payee = "Corner Shop", description = null, isPending = false, rawJson = null,
            )), closing = day to 118_750L)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            fun wordmarkLeft(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().minOf { it.boundsInRoot.left }

            tab("Settings")
            until("settings") { shows("YOUR BUDGET") }
            val withPenny = wordmarkLeft("settings")

            tab("Accounts")
            until("Penny on Accounts") { shows("No statement for Everyday since") }
            assertTrue("her link", compose.onAllNodes(hasText("Import ›") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
            assertTrue("not in the banner as well", wordmarkLeft("accounts") < withPenny)
        }
    }
}
