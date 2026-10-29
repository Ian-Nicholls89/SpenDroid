package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.spendroid.ui.theme.BudgetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.7.2.1: Regular's five tabs at a large text size - on the user's phone "Ignored" was squeezed
 * into a column of letters. Each stays on one line, the row scrolls, and the last can be reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h300dp-xxhdpi", application = android.app.Application::class)
class DotTabsFitTest {

    @get:Rule val compose = createComposeRule()

    private val tabs = listOf("Upcoming", "Bills", "Renewals", "Income", "Ignored")

    private fun lines(label: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    @Test
    fun `every tab on one line at twice the text size, and the last one reachable`() {
        compose.setContent {
            BudgetTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    var tab by remember { mutableIntStateOf(2) }
                    DotTabs(tabs, tab, { tab = it }, androidx.compose.ui.Modifier.background(com.spendroid.ui.theme.Charcoal.Background))
                }
            }
        }
        tabs.forEach { assertEquals("$it on one line", 1, lines(it)) }
        compose.onRoot().captureRoboImage("build/screenshots/dot-tabs-large-text.png")
        compose.onNodeWithText("Ignored").performScrollTo().assertIsDisplayed().performClick()
    }
}
