package com.spendroid.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.RecurringAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 0.7.3: Regular in three tabs - a bill switched off under Bills' "Left out", income from Each
 * month's figure - and Settings as one list whose sections open and close. Made-up names and figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TidyRunTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()

    /** An account with pay coming in and a car park paid monthly - switched off. */
    private fun seed() = runBlocking {
        app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        com.spendroid.data.Choices.awaitLoaded()
        val repo = app.repository
        val account = repo.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
        val today = LocalDate.now()
        fun tx(id: String, date: LocalDate, minor: Long, payee: String) = TransactionEntity(
            accountId = account.id, transactionId = com.spendroid.domain.DuplicateCheck.IMPORTED_PREFIX + id, bookingDate = date.toString(),
            valueDate = null, amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
        )
        val rows = (1..4L).flatMap { m ->
            listOf(
                tx("pay$m", today.minusMonths(m).withDayOfMonth(25), 185_000, "MADE-UP EMPLOYER LTD"),
                tx("park$m", today.minusMonths(m).withDayOfMonth(3), -1_250, "MADE-UP PARKING"),
            )
        }
        repo.addImported(rows, closing = today.minusDays(1) to 120_000L)
        val park = RecurringAnalyzer.analyze(rows).first { it.payee.contains("PARKING") }
        repo.setRuleIgnored(park.key, true)
    }

    private fun settle() = repeat(10) { compose.mainClock.advanceTimeBy(150) }

    private fun until(what: String, condition: () -> Boolean) {
        repeat(40) {
            if (condition()) return
            settle()
            // The figures are worked out off the main thread, in real time.
            Thread.sleep(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertTrue(what, condition())
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(label: String, lowest: Boolean = false, substring: Boolean = false) {
        val matcher = hasText(label, substring = substring) and hasClickAction()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue("nothing to tap saying $label", nodes.isNotEmpty())
        val at = if (lowest) nodes.indices.maxBy { nodes[it].boundsInRoot.top } else nodes.indices.minBy { nodes[it].boundsInRoot.top }
        // Pressed by its click action: it may be below the fold, and scrolling to it waits for the
        // screen to be still, which with Penny moving it never is.
        compose.onAllNodes(matcher)[at].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun `Regular - three tabs, a bill left out under Bills, income from Each month`() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            tap("Regular", lowest = true)
            until("Regular") { shows("Upcoming") && shows("Income ›") }
            assertTrue("no Ignored tab", !shows("Ignored"))

            tap("Bills")
            until("left out") { shows("Left out · 1") }
            tap("Left out · 1")
            until("the bill") { shows("MADE-UP PARKING") && shows("Switched off") }

            tap("Income ›", substring = true)
            until("income sheet") { shows("Your income") && shows("MADE-UP EMPLOYER LTD") }
            assertTrue(shows("Add income by hand"))
        }
    }

    @Test
    fun `Settings - one list, a section opened and back`() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            tap("Settings", lowest = true)
            until("the list") { shows("YOUR BUDGET") && shows("YOUR DATA") }
            tap("Privacy & security")
            until("the section") { shows("APP LOCK") }
            assertTrue("the list has gone", !shows("YOUR DATA"))
            compose.onNode(androidx.compose.ui.test.hasContentDescription("Back to settings")).performClick()
            settle()
            until("back to the list") { shows("YOUR DATA") && !shows("APP LOCK") }

            // Every section opens.
            listOf("Look", "Budget", "Notifications", "Card alerts", "Bank & syncing", "Backups & data", "Watch", "Computer link", "About").forEach { name ->
                tap(name)
                until("$name open") { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back to settings")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(androidx.compose.ui.test.hasContentDescription("Back to settings")).performClick()
                settle()
                until("back from $name") { shows("YOUR DATA") }
            }
        }
    }
}
