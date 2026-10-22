package com.spendroid.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.6.5: the widgets' ↻ is seen to work - "Refreshing…", then when the bank and the widget each last moved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WidgetRefreshTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun at(h: Int, m: Int) = java.time.LocalDate.now().atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `the line says when the bank's figures came and when the widget was drawn`() {
        WidgetRefresh.drawn(context, at(10, 30))
        assertEquals("Bank 08:02 · widget 10:30", WidgetRefresh.label(context, at(8, 2), at(10, 31)))
        // No bank link at all: only the widget's time.
        assertEquals("Widget 10:30", WidgetRefresh.label(context, null, at(10, 31)))
    }

    @Test
    fun `while a refresh runs it says so, and Penny is syncing`() {
        context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE).edit().putLong("started", at(10, 30)).commit()
        assertEquals("Refreshing…", WidgetRefresh.label(context, at(8, 2), at(10, 30) + 500))
        assertTrue(WidgetRefresh.refreshing(context, at(10, 30) + 500))
        // A refresh that never finished doesn't say so for ever.
        assertEquals(false, WidgetRefresh.refreshing(context, at(10, 31)))
    }
}
