package com.spendroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.7.1: whatever Penny says is seen whole - never cut off - on a small phone with the text as
 * large as Android lets it go. Her lines are kept to 70 characters ([PennyCycleTest]); these are
 * longer than any she says, at the biggest figures and longest shop names, to show the bubble
 * grows to fit rather than clips.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measuring, as on a phone: without it every letter is a guess.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h568dp-mdpi", application = android.app.Application::class)
class PennyBubbleFitTest {
    @get:Rule val compose = createComposeRule()

    private val worst = listOf(
        "£9,999.99 from your account today - £9,999.99 of today's £9,999.99 left.",
        "Supercalifragilisticexp is due on payday, so it's next month's money.",
        "Nothing left until payday, Wed 25 Nov - spend now is next cycle's. And a good deal more besides, well past any line she has, to see it wrap and wrap again.",
    )

    private fun fits(fontScale: Float, width: Int) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Column(Modifier.width(width.dp)) {
                    PennyStories(DroidFace(), worst + "A fourth line, not said: three at most.", actions = worst.map { "See it" to {} })
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        repeat(worst.size) { i ->
            val line = worst[i]
            val node = compose.onNode(hasText(line)).fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
            val text = layout.single()
            assertFalse("$line: overflows its text box at ${fontScale}x: ${text.size} lines ${text.lineCount} w ${text.didOverflowWidth} h ${text.didOverflowHeight}", text.hasVisualOverflow)
            assertEquals("$line: every character laid out", line.length, text.getLineEnd(text.lineCount - 1))
            // Not clipped by anything round it: shown at the size it's laid out at, on screen.
            val shown = node.boundsInRoot
            assertEquals("$line: clipped at ${fontScale}x", node.size.height.toFloat(), shown.height, 1f)
            assertEquals("$line: clipped at ${fontScale}x", node.size.width.toFloat(), shown.width, 1f)
            assertTrue("$line: off the screen's side", shown.right <= root.right + 0.5f)
            // Its link, whole, under it.
            assertTrue("$line: its link", compose.onAllNodes(hasText("See it ›")).fetchSemanticsNodes().isNotEmpty())
            compose.onNode(hasText(line)).performClick()
            compose.waitForIdle()
        }
        // Back round to the first: the fourth line isn't one of hers to say.
        assertTrue(compose.onAllNodes(hasText(worst[0])).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("A fourth line, not said: three at most.")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText(worst[0])).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.Text) != null)
    }

    @Test fun `seen whole at normal text size`() = fits(1f, 320)

    @Test fun `seen whole with the largest text, on a narrow screen`() = fits(2f, 320)

    @Test fun `seen whole on the phone's width with large text`() = fits(1.3f, 411)
}
