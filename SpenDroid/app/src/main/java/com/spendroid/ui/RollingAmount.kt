package com.spendroid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.spendroid.ui.theme.Motion

/**
 * A money figure whose digits roll to a new value, like a mechanical counter.
 *
 * A number that changes in place is easy to miss, and the change is usually the news - a sync
 * took twelve pounds off. Each digit that changed rolls; the ones that did not stay put, so
 * the eye goes straight to what moved. On first appearance the digits roll up from zero.
 *
 * Positions are counted from the right, so a figure that gains or loses a digit keeps its
 * pence and pounds lined up rather than shifting everything along by one.
 */
@Composable
fun RollingAmount(
    minor: Long,
    currency: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val text = formatMoney(minor, currency)
    // Starts as the same shape with every digit zero, so the first frame rolls up to the value.
    // Saved rather than remembered, so returning to a tab shows the figure where it was
    // instead of rolling it up from nothing every time.
    var shown by rememberSaveable { mutableStateOf(text.map { if (it.isDigit()) '0' else it }.joinToString("")) }
    var shownMinor by rememberSaveable { mutableLongStateOf(0L) }
    val rising = minor >= shownMinor
    LaunchedEffect(text) {
        shown = text
        shownMinor = minor
    }

    val digitStyle = style.copy(fontFeatureSettings = "tnum")
    Row(modifier = modifier.clearAndSetSemantics { contentDescription = text }) {
        shown.forEachIndexed { index, char ->
            key(shown.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            // Rising values roll up from below, falling ones drop from above.
                            val from = if (rising) 1 else -1
                            (slideInVertically(Motion.arrive(Motion.LONG + index * 40)) { h -> from * h } + fadeIn(Motion.arrive()))
                                .togetherWith(slideOutVertically(Motion.change()) { h -> -from * h } + fadeOut(Motion.change(Motion.SHORT)))
                        } else {
                            fadeIn(Motion.arrive()).togetherWith(fadeOut(Motion.change(Motion.SHORT)))
                        }.using(SizeTransform(clip = true))
                    },
                    label = "digit",
                ) { c ->
                    Text(c.toString(), style = digitStyle, color = color, fontWeight = fontWeight)
                }
            }
        }
    }
}
