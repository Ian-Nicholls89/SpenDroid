package com.spendroid.wear.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.wear.BudgetActivity
import com.spendroid.wear.ComplicationWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Watch round two (watch 0.1.16, phone 0.8.7): the month as the headline with the account a tap
 * away, payees on their own line, Coming up as a list, the week's amounts, and the complications
 * worded for the month and the account. Made-up figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w228dp-h228dp-round-watch-xhdpi")
class WatchRoundTwoTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun settle() = repeat(10) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(150)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(what: String, condition: () -> Boolean) {
        repeat(20) { if (condition()) return; settle() }
        assertTrue("$what - showing: " + compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().map { n ->
            (n.config.find { it.key == androidx.compose.ui.semantics.SemanticsProperties.Text }?.value as? List<*>)?.joinToString("|")
        }, condition())
    }

    private fun next() {
        compose.onRoot().performTouchInput { swipeUp() }
        settle()
    }

    private fun launch(block: () -> Unit) {
        ActivityScenario.launch(BudgetActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            block()
        }
    }

    @Test
    fun `the month leads, and its account line opens the account's page`() {
        SampleReading.month().save(context)
        launch {
            until("the month's figure") { shows("£312.40") && shows("left this month") }
            until("the account's share") { shows("£150 from account ›") }
            compose.onAllNodes(hasText("£150 from account", substring = true) and hasClickAction())[0].performClick()
            settle()
            until("the account's page") { shows("£150.20") && shows("left, in the black") }
            assertTrue(shows("after £594 of card bills"))
            assertTrue(shows("the rest of the month on cards"))
            // And a turn of the crown from it carries on to Today.
            next(); until("today") { shows("spent today") }
        }
    }

    @Test
    fun `without cards there is no account page, and the hero says until when`() {
        SampleReading.reading { putString("leftLabel", "left until Fri 23 Oct") }.save(context)
        launch {
            until("the figure") { shows("£312.40") && shows("left until Fri 23 Oct") }
            assertTrue(!shows("from account"))
            next(); until("today, straight after") { shows("spent today") }
        }
    }

    @Test
    fun `payees have their own line, coming up is a list, and the week shows its amounts`() {
        SampleReading.month().save(context)
        launch {
            until("the figure") { shows("£312.40") }
            // Hero, account, today, categories, then recent.
            repeat(4) { next() }
            until("recent, the category beside the amount") { shows("PORTON STORES") && shows("Work lunches") && shows("£4.50") }
            next(); until("the week's total") { shows("£149.05 in 7 days") }
            // Today's amount and the biggest day's, over their bars.
            assertTrue(shows("£21") && shows("£41"))
            next(); next()
            until("coming up, as a list") { shows("PUREGYM") && shows("OCTOPUS ENERGY") && shows("~£96") && shows("all 3") }
        }
    }

    @Test
    fun `the complications speak of the month and the account`() {
        val month = ComplicationWords.of(SampleReading.month { putString("pace", "TIGHT") }, account = false)
        assertEquals("£312", month.figure)
        assertEquals("month", month.title)
        assertEquals("£312 left this month · £14.20 a day", month.long)
        assertEquals("£312 left this month, a little behind", month.spoken)
        // Every ring fills with what's spent.
        assertEquals(0.38f, month.spent, 0.0001f)

        val account = ComplicationWords.of(SampleReading.month(), account = true)
        assertEquals("£150", account.figure)
        assertEquals("acct", account.title)
        assertEquals("£150 from your account · £6.82 a day", account.long)
        assertEquals("£150 from your account, well behind", account.spoken)
        assertEquals(0.55f, account.spent, 0.0001f)
        assertEquals("OVER", account.pace)

        // No cards: the account is the month, worded as before.
        val plain = ComplicationWords.of(SampleReading.reading(), account = true)
        assertEquals("£312", plain.figure)
        assertEquals("left", plain.title)
        assertEquals("£312 left · £14.20 a day", plain.long)
        assertEquals(0.38f, plain.spent, 0.0001f)
    }
}
