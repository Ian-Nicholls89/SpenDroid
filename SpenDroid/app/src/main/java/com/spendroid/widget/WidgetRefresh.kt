package com.spendroid.widget

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * A widget's ↻, seen to work. It redraws every widget from what's stored - no bank call, the
 * bank's few syncs a day are kept for the scheduled ones - and used to change nothing you could
 * see, so it seemed to do nothing. Now Penny takes her syncing pose and the line says
 * "Refreshing…" for a moment, then the widgets show when the bank's figures and the widget each
 * last moved: "Bank 08:02 · widget 10:30".
 */
internal object WidgetRefresh {

    private const val PREFS = com.spendroid.data.Stores.WIDGET_REFRESH
    private const val STARTED = "started"
    private const val DRAWN = "drawn"

    /** Long enough to see her syncing; a refresh from what's stored takes no time at all. */
    private const val SHOWN_MS = 1_200L
    /** Past this, a refresh that never said it finished is taken as over. */
    private const val STUCK_MS = 30_000L

    suspend fun start(context: Context) {
        prefs(context).edit().putLong(STARTED, System.currentTimeMillis()).commit()
        refreshWidgets(context)
        delay(SHOWN_MS)
        prefs(context).edit().putLong(STARTED, 0L).commit()
        refreshWidgets(context)
    }

    /** Whether a ↻ is under way: Penny shows it, syncing. */
    fun refreshing(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        prefs(context).getLong(STARTED, 0L).let { it > 0L && now - it < STUCK_MS }

    /** Noted at every redraw of the widgets, for "widget 10:30". */
    fun drawn(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(DRAWN, now).apply()
    }

    /**
     * The widgets' line about freshness: "Refreshing…" while a ↻ runs; else when the bank's
     * figures last came ([bankAt], from the accounts' syncs) and when the widget was last drawn.
     */
    /** Just the bank's time, for a widget whose header is short of room (0.8.0): "08:00". */
    fun bankTime(context: Context, bankAt: Long?, now: Long = System.currentTimeMillis()): String? {
        if (refreshing(context, now)) return "Refreshing…"
        return bankAt?.let(::time)
    }

    fun label(context: Context, bankAt: Long?, now: Long = System.currentTimeMillis()): String? {
        if (refreshing(context, now)) return "Refreshing…"
        val drawn = prefs(context).getLong(DRAWN, 0L).takeIf { it > 0L } ?: now
        val bank = bankAt?.let { "Bank ${time(it)}" }
        return listOfNotNull(bank, "widget ${time(drawn)}").joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    private fun time(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return at.format(if (at.toLocalDate() == LocalDate.now()) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("d MMM"))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
