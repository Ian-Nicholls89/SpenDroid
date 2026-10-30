package com.spendroid.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.data.CardCredits
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
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
 * 0.8.0: money onto a card that isn't paying it off - Penny asks whether it was a refund, the
 * answer is kept, and "No" can be changed to "Yes" straight away. Made-up names and figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CardRefundRunTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()

    private fun settle() = repeat(10) { compose.mainClock.advanceTimeBy(150) }

    private fun until(what: String, condition: () -> Boolean) {
        repeat(40) {
            if (condition()) return
            settle()
            Thread.sleep(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertTrue(what, condition())
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun `Penny asks about money onto a card, and the answer is kept and can be changed`() {
        val creditKey = runBlocking {
            app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
            com.spendroid.data.Choices.awaitLoaded()
            val repo = app.repository
            val account = repo.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
            val card = repo.addManualAccount("Shop Card", "Made-up Card Co", AccountType.CREDIT_CARD, -4_000)
            val today = LocalDate.now()
            fun tx(acc: String, id: String, date: LocalDate, minor: Long, payee: String) = TransactionEntity(
                accountId = acc, transactionId = com.spendroid.domain.DuplicateCheck.IMPORTED_PREFIX + id, bookingDate = date.toString(),
                valueDate = null, amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
            )
            repo.addImported(
                (1..3L).map { m -> tx(account.id, "pay$m", today.minusMonths(m).withDayOfMonth(1), 200_000, "MADE-UP EMPLOYER LTD") } +
                    tx(account.id, "pay0", today.withDayOfMonth(1), 200_000, "MADE-UP EMPLOYER LTD"),
                closing = today to 120_000L,
            )
            // On the cycle's first day: in this pay period whatever today is.
            val refund = tx(card.id, "back", today.withDayOfMonth(1), 1_250, "MADE-UP SHOE SHOP")
            repo.addImported(listOf(tx(card.id, "shoes", today.withDayOfMonth(1), -5_000, "MADE-UP SHOE SHOP"), refund), closing = today to -3_750L)
            "${card.id}|${refund.transactionId}"
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.autoAdvance = false
            settle()
            until("Penny asks") { shows("Was it a refund?") }
            assertTrue(shows("£12.50 came onto Shop Card from MADE-UP SHOE SHOP"))
            tap("No, a payment to the card")
            until("noted") { shows("Noted: not a refund") }
            assertEquals(false, CardCredits(app).answers()[creditKey])
            // Every answer can be changed.
            tap("Change: it was a refund")
            until("changed") { shows("Noted: a £12.50 refund") }
            assertEquals(true, CardCredits(app).answers()[creditKey])
        }
    }
}
