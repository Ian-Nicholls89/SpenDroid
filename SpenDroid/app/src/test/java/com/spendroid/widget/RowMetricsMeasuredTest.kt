package com.spendroid.widget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.8.3: the widget measures its text on the phone - at a large text size, the user's 4x2 still gets two-line rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class RowMetricsMeasuredTest {

    @Test
    fun `measured at a large text size, rows grow with it and three still fit the user's widget`() {
        RuntimeEnvironment.setFontScale(1f)
        val normal = RowMetrics.measured(ApplicationProvider.getApplicationContext())
        RuntimeEnvironment.setFontScale(1.3f)
        val large = RowMetrics.measured(ApplicationProvider.getApplicationContext())
        assertTrue("text grows: $normal -> $large", large.compact > normal.compact)
        // Close to the estimate the tests reason with.
        assertEquals(RowMetrics.standard(1.3f).compact, large.compact, 4f)
        assertEquals(RowDensity.COMPACT to 3, rowLayout(193f, 3, large))
    }
}
