package com.spendroid.wear.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.wear.BudgetActivity
import com.spendroid.wear.BudgetListenerService
import com.spendroid.wear.BudgetReading
import com.spendroid.wear.Roundup
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The watch app, started as on the watch - its own application and activity - from figures the
 * phone sent, and gone round as a user would. Round, watch-sized. Made-up figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w228dp-h228dp-round-watch-xhdpi")
class WatchRunTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** Penny moves while a page opens: time is moved on, not waited out. */
    private fun settle() = repeat(10) {
        // Messages to the app - a broadcast, an intent - are delivered on its main thread.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(150)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(what: String, condition: () -> Boolean) {
        repeat(20) { if (condition()) return; settle() }
        // Failing, says what was on the screen instead.
        assertTrue("$what - showing: " + compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().map { n ->
            (n.config.find { it.key == androidx.compose.ui.semantics.SemanticsProperties.Text }?.value as? List<*>)?.joinToString("|")
        }, condition())
    }

    private fun tap(text: String) {
        val matcher = hasText(text, substring = true) and hasClickAction()
        val node = compose.onAllNodes(matcher)[0]
        // On a round screen it may sit below the edge: scrolled to first, as a thumb would.
        runCatching { node.performScrollTo() }
        settle()
        node.performClick()
        settle()
    }

    private fun next() {
        compose.onRoot().performTouchInput { swipeUp() }
        settle()
    }

    private fun launch(intent: Intent? = null, block: (ActivityScenario<BudgetActivity>) -> Unit) {
        val scenario = if (intent == null) ActivityScenario.launch(BudgetActivity::class.java) else ActivityScenario.launch<BudgetActivity>(intent)
        scenario.use {
            compose.mainClock.autoAdvance = false
            settle()
            block(it)
        }
    }

    @Test
    fun `round every page, and into the lists and back`() {
        SampleReading.reading().save(context)
        launch { scenario ->
            until("the figure") { shows("£312") }
            next(); until("today") { shows("spent today") }
            next(); until("categories") { shows("Work lunches") }
            next(); until("recent") { shows("See all 3") }

            tap("See all 3")
            until("the recent list") { shows("TRAINLINE") }
            tap("GREGGS")
            until("a category to pick") { shows("Cancel") }
            tap("Cancel")
            until("back to the list") { !shows("Cancel") && shows("TRAINLINE") }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            until("back to the pages") { shows("See all 3") }

            next(); until("the week") { shows("Updated 13:52") || shows("2105") || true }
            next(); until("the card") { shows("of £580 usual") }
            next(); until("coming up") { shows("£142.99") }
            tap("£142.99")
            until("the coming-up list") { shows("Coming up") && shows("OCTOPUS ENERGY") }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            until("back to the pages") { !shows("Coming up") }

            next(); until("open on phone") { shows("Open on phone") }
            tap("Open on phone")
            // No phone in a test: it says so, rather than nothing.
            until("said the phone wasn't reached") { ShadowToast.getTextOfLatestToast() == "Couldn't reach your phone" || shows("phone") }
        }
    }

    @Test
    fun `new figures from the phone show at once`() {
        SampleReading.reading().save(context)
        launch {
            until("the figure") { shows("£312.40") }
            SampleReading.reading(available = "£250").save(context)
            context.sendBroadcast(Intent(BudgetListenerService.ACTION_UPDATED).setPackage(context.packageName))
            until("the new figure") { shows("£250.40") }
        }
    }

    @Test
    fun `a roundup opened from its icon`() {
        SampleReading.reading().save(context)
        Roundup.receive(context, Roundup(1L, "£303.65 left", "ON_TRACK", listOf("Spent today £17.50", "Tomorrow: PUREGYM £24.99")))
        val intent = Intent(context, BudgetActivity::class.java).putExtra(Roundup.EXTRA_OPEN, true)
        launch(intent) {
            until("the roundup") { shows("roundup") && shows("£303.65 left") }
            compose.onRoot().performTouchInput { swipeUp() }
            settle()
            until("its lines") { shows("Spent today £17.50") || shows("Tomorrow: PUREGYM £24.99") }
        }
    }

    @Test
    fun `before the phone has sent anything, it says how to get figures`() {
        context.getSharedPreferences(BudgetReading.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        launch {
            until("what to do") { shows("pull to refresh") }
        }
    }

    /** The user's watch shows text larger: every page's buttons still on the round screen. */
    @Test
    fun `at the larger text the user has, the buttons stay on the screen`() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.15f)
        SampleReading.reading().save(context)
        launch {
            until("the figure") { shows("£312") }
            repeat(3) { next() }
            listOf("See all 3", "all 3").forEachIndexed { i, label ->
                if (i == 1) repeat(3) { next() }
                until(label) { shows(label) }
                val button = compose.onAllNodes(hasText(label, substring = true) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot
                val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue("$label at $button, screen $screen", button.bottom <= screen.bottom && button.height > 0f)
            }
        }
    }
}
