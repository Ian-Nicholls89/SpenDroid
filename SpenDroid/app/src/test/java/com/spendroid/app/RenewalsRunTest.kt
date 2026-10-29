package com.spendroid.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.data.RenewalStore
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Renewals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 0.7.2: renewals, the whole app round - Penny saying one's due on Home and Regular, "Sorted" on
 * Upcoming, one added from the empty tab, and her question when a payment looks like one renewing.
 * Made-up names and figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RenewalsRunTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()
    private val today = LocalDate.now()

    /** An account with a bill, so Home has its figures and Regular its tabs. */
    private fun seed(): String = runBlocking {
        app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        // The app loads the user's choices as it starts: renewals saved after that, or the load overwrites them.
        com.spendroid.data.Choices.awaitLoaded()
        val account = app.repository.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
        app.repository.addManualRule(
            ManualRecurringRuleEntity(
                id = "manual-1", payee = "GYM CLUB", direction = "OUT", amountMinor = 2_500, currency = "GBP",
                cadence = "MONTHLY", anchorDay = 3, startDate = "2026-01-03", accountId = account.id,
            ),
        )
        account.id
    }

    private fun settle() = repeat(10) { compose.mainClock.advanceTimeBy(150) }

    private fun until(what: String, condition: () -> Boolean) {
        repeat(40) {
            if (condition()) return
            settle()
            // The figures are worked out again off the main thread, in real time.
            Thread.sleep(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertTrue(what, condition())
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The lowest tappable thing saying exactly [label]: the tab at the foot, over a heading that says it too. */
    private fun tap(label: String, lowest: Boolean = true) {
        val matcher = hasText(label) and hasClickAction()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue("nothing to tap saying $label", nodes.isNotEmpty())
        val at = if (lowest) nodes.indices.maxBy { nodes[it].boundsInRoot.top } else nodes.indices.minBy { nodes[it].boundsInRoot.top }
        val node = compose.onAllNodes(matcher)[at]
        // Below the fold in a list or a sheet: scrolled to first, or the tap lands off the screen.
        runCatching { node.performScrollTo() }
        settle()
        node.performClick()
        settle()
    }

    @Test
    fun `due - said on Home and Regular, then sorted from Upcoming`() {
        seed()
        RenewalStore(app).save(Renewals.Renewal(id = "car", name = "Car insurance", ends = today.plusWeeks(4), lastMinor = 45_000))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            // Penny says it first on Home - it needs the user - typed out over a moment.
            until("on Home") { shows("Car insurance ends in 4 weeks - time to compare quotes.") }

            tap("Regular")
            until("on Upcoming") { shows("Remind me in 2 weeks") }
            assertTrue(shows("Renewing"))
            tap("Sorted", lowest = false)
            assertTrue("saved", RenewalStore(app).get("car")!!.sorted)
            until("sorted") { !shows("Remind me in 2 weeks") }
            until("Penny's said it") { !shows("Car insurance ends in 4 weeks") }

            tap("Renewals", lowest = false)
            until("the tab") { shows("1 renewal") }
            assertTrue(shows("SORTED"))
            // The usual ones stay a tap away, less the one already there (0.7.2.1).
            assertTrue(shows("+ Home insurance"))
            assertTrue(!shows("+ Car insurance"))
        }
    }

    @Test
    fun `one added from the empty tab, with a tap`() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            tap("Regular")
            until("Regular") { shows("Upcoming") }
            tap("Renewals", lowest = false)
            until("empty") { shows("No renewals yet") }
            tap("+ TV licence", lowest = false)
            until("the sheet") { shows("Add a renewal") }
            tap("Pick the date", lowest = false)
            until("the calendar") { shows("Cover ends") && compose.onAllNodes(hasText("Set") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            tap("Set")
            until("date set") { shows("Change") }
            tap("Save")
            val saved = RenewalStore(app).all().single()
            until("listed") { shows("1 renewal") }
            assertEquals("TV licence", saved.name)
            assertEquals(today.plusMonths(6), saved.ends)
        }
    }

    @Test
    fun `a payment that looks like it - Penny asks, and never takes its date for the end`() {
        val account = seed()
        val ends = today.plusDays(10)
        val paid = today.minusDays(3)
        RenewalStore(app).save(Renewals.Renewal(id = "car", name = "Car insurance", ends = ends, lastMinor = 45_000))
        runBlocking {
            app.repository.addImported(listOf(TransactionEntity(
                accountId = account, transactionId = com.spendroid.domain.DuplicateCheck.IMPORTED_PREFIX + "made-up-ins", bookingDate = paid.toString(), valueDate = null,
                amountMinor = -46_218, currency = "GBP", payee = "MADE-UP INSURANCE CO", description = null, isPending = false, rawJson = null,
            )), closing = paid to 73_782L)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            until("Penny's question") { shows("Was this it renewing") }
            tap("Yes, renewed")
            until("the new end") { shows("When does the new cover end?") }
            val format = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy")
            assertTrue("a year on from the old end", shows(ends.plusYears(1).format(format)))
            assertTrue("a year on from the payment", shows(paid.plusYears(1).format(format)))
            tap(ends.plusYears(1).format(format))
            until("noted") { shows("Noted: car insurance ends") }
            val r = RenewalStore(app).get("car")!!
            assertEquals(ends.plusYears(1), r.ends)
            assertEquals(46_218L, r.renewedBy.single().amountMinor)
            assertEquals(46_218L, r.lastMinor)
        }
    }
}
