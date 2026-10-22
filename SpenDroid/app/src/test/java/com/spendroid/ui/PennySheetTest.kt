package com.spendroid.ui

import androidx.compose.runtime.mutableStateOf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.domain.NotificationSpend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.6.2: the question sheet - answering the last question still says it back, and only then closes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PennySheetTest {
    @get:Rule val compose = createComposeRule()

    private fun spend(id: String, at: Long) = SeenSpendEntity(
        id = id, source = "bank", seenAt = at, amountMinor = -1_000, currency = "GBP", merchant = "TESCO", accountId = "a",
    )

    @Test
    fun `the last answer is said back before the sheet closes`() {
        val now = System.currentTimeMillis()
        val seen = mutableStateOf(listOf(spend("p1", now - 60_000), spend("p2", now)))
        var done = 0
        compose.setContent {
            val questions = NotificationSpend.possibleRepeats(seen.value).map { PennyQuestion.Repeat(it) }
            PennyQuestionBody(
                questions, emptyList(), emptyList(),
                PennyAnswers(onRepeat = { id, twin, once ->
                    // As the app does: the answer lands, and the question is gone from the list.
                    seen.value = seen.value.map { if (it.id == id) it.copy(dismissed = once, pairedWithId = twin, confirmedSeparate = !once) else it }
                }),
                onLater = {}, onDone = { done++ },
            )
        }
        compose.onNodeWithText("Once, remove the extra").performClick()
        compose.mainClock.autoAdvance = false
        // A few frames on - well inside the noted page's four seconds - it's being said back.
        var said = false
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodes(androidx.compose.ui.test.hasText("PENNY'S NOTED IT")).fetchSemanticsNodes().isNotEmpty()) said = true
        }
        org.junit.Assert.assertTrue("the answer is said back", said)
        assertEquals(0, done)
        // It moves on by itself - and with nothing left, closes.
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(1, done)
        assertFalse(NotificationSpend.possibleRepeats(seen.value).isNotEmpty())
    }
}
