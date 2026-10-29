package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.spendroid.ui.theme.BudgetTheme
import com.spendroid.ui.theme.Charcoal
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.7.2.1: a bar's figure at a large text size - on the user's phone "59% of income on bills" spilled
 * out of Each month's bar. The bar grows to hold it, at every text size up to twice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h400dp-xxhdpi", application = android.app.Application::class)
class BarLabelFitTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the figure stays inside its bar at every text size`() {
        val scales = listOf(1f, 1.3f, 1.6f, 2f)
        compose.setContent {
            BudgetTheme {
                val density = LocalDensity.current
                Column(Modifier.background(Charcoal.Background).padding(16.dp)) {
                    scales.forEach { scale ->
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            LabelledBar(0.59f, "59% of income on bills $scale", Color(0xFF43B05C), Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/bar-label-sizes.png")
        scales.forEach { scale ->
            val label = "59% of income on bills $scale"
            val bar = compose.onNode(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNode().boundsInRoot
            val text = compose.onNode(androidx.compose.ui.test.hasText(label)).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val top = text.boundsInRoot.top
            val bottom = top + results.single().size.height
            assertTrue("at $scale: text $top..$bottom, bar ${bar.top}..${bar.bottom}", top >= bar.top - 0.5f && bottom <= bar.bottom + 0.5f)
        }
    }
}
