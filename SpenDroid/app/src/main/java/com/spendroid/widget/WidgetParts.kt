package com.spendroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.spendroid.data.db.AccountEntity
import com.spendroid.domain.BudgetPace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pieces shared by the widgets: the images Glance cannot draw itself, the refresh action and
 * the small labels every widget carries.
 *
 * Glance has no canvas, so anything that is a shape rather than a box is drawn once into a
 * bitmap and shown as an image - the same approach the 2x2 ring has always used.
 */

/** Opens the Spending tab filtered to this category, from a tap on a widget row. */
val CategoryParam = ActionParameters.Key<String>(MAIN_EXTRA_CATEGORY)

/** The intent extra MainActivity reads; ActionParameters are delivered as extras by name. */
const val MAIN_EXTRA_CATEGORY = "com.spendroid.extra.CATEGORY"

/**
 * Redraws every widget from what is already stored. Deliberately no bank call: the bank allows
 * only a few syncs a day, and a button that spent them would soon stop working.
 */
class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        refreshWidgets(context)
        refreshCardWidgets(context)
    }
}

/**
 * "Updated 09:29", or the date once it is no longer today, so staleness is visible.
 *
 * The oldest sync among the current accounts, where salaries land and the budget is decided -
 * not the newest of any account. The newest let a card that synced at 14:08 vouch for a
 * current account stuck on 09:29, and the figures looked fresher than they were.
 */
fun updatedLabel(accounts: List<AccountEntity>, zone: ZoneId = ZoneId.systemDefault()): String? {
    val current = accounts.filter { it.accountType == com.spendroid.data.db.AccountType.PERSONAL }.ifEmpty { accounts }
    val latest = current.minOfOrNull { it.lastSynced }?.takeIf { it > 0L } ?: return null
    val at = Instant.ofEpochMilli(latest).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) {
        "Updated ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    } else {
        "Updated ${at.format(DateTimeFormatter.ofPattern("d MMM"))}"
    }
}

/** The pace in words and a shape, so the colour is never the only thing saying it. */
fun paceLabel(pace: BudgetPace.Pace): String = when (pace) {
    BudgetPace.Pace.ON_TRACK -> "● On track"
    BudgetPace.Pace.TIGHT -> "◆ Tight"
    BudgetPace.Pace.OVER -> "▲ Over"
}

private fun paint(color: Int, stroke: Float? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    if (stroke != null) {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
}

/**
 * The app's ring: the pale arc is how far through the cycle today is, the white one how much
 * of the budget is gone. The gap between them is the reading.
 */
fun ringBitmap(used: Float, elapsed: Float?): Bitmap {
    val px = 220
    val stroke = 22f
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val box = RectF(stroke / 2f, stroke / 2f, px - stroke / 2f, px - stroke / 2f)
    canvas.drawArc(box, 0f, 360f, false, paint(0x42FFFFFF, stroke))
    elapsed?.takeIf { it > 0f }?.let { canvas.drawArc(box, -90f, 360f * it.coerceIn(0f, 1f), false, paint(0x80FFFFFF.toInt(), stroke)) }
    used.takeIf { it > 0f }?.let { canvas.drawArc(box, -90f, 360f * it.coerceIn(0f, 1f), false, paint(0xFFFFFFFF.toInt(), stroke)) }
    return bitmap
}

/** Seven bars, oldest to today, with today in full white and the rest softer. */
fun weekBitmap(values: List<Long>): Bitmap {
    val w = 420
    val h = 90
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (values.isEmpty()) return bitmap
    val canvas = Canvas(bitmap)
    val gap = 10f
    val barW = (w - gap * (values.size - 1)) / values.size
    val peak = values.max().coerceAtLeast(1L).toFloat()
    val radius = 7f
    values.forEachIndexed { i, v ->
        val left = i * (barW + gap)
        // A day with nothing spent still gets a sliver, so it reads as zero rather than missing.
        val top = h - maxOf(5f, v / peak * h)
        val color = if (i == values.lastIndex) 0xFFFFFFFF.toInt() else 0x8CFFFFFF.toInt()
        canvas.drawRoundRect(RectF(left, top, left + barW, h + radius), radius, radius, paint(color))
    }
    return bitmap
}

/**
 * The days until payday as a line: done in white, to come in grey, a marker for today and a
 * dot on each day a bill leaves. Card bills are blue, since their amount is still moving.
 */
fun timelineBitmap(today: Float, bills: List<Pair<Float, Boolean>>, aspect: Float = 10f): Bitmap {
    val h = 60
    val w = (h * aspect.coerceIn(2f, 30f)).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = 14f
    val y = h / 2f
    val span = w - inset * 2
    canvas.drawLine(inset, y, w - inset, y, paint(0x4DFFFFFF, 5f))
    canvas.drawLine(inset, y, inset + span * today, y, paint(0xFFFFFFFF.toInt(), 5f))
    bills.forEach { (at, isCard) ->
        val x = inset + span * at
        canvas.drawCircle(x, y, 13f, paint(0x40000000))
        canvas.drawCircle(x, y, 10f, paint(if (isCard) 0xFF9EC5F4.toInt() else 0xFFFFFFFF.toInt()))
    }
    val tx = inset + span * today
    canvas.drawLine(tx, y - 20f, tx, y + 20f, paint(0xFFFFFFFF.toInt(), 4f))
    return bitmap
}

/**
 * A card's statement against its limit: spent solid, the projection hatched after it, a tick
 * where an even pace would be by today, and a notch for the limit once the bar runs past it.
 */
fun cardRailBitmap(rail: WidgetGeometry.CardRail, fill: Int, track: Int, tick: Int, over: Boolean): Bitmap {
    val w = 600
    val h = 36
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val barTop = 11f
    val barBottom = 25f
    val r = 7f
    canvas.drawRoundRect(RectF(0f, barTop, w.toFloat(), barBottom), r, r, paint(track))

    val projectedEnd = w * rail.projected.coerceIn(0f, 1f)
    val spentEnd = w * rail.spent.coerceIn(0f, 1f)
    if (projectedEnd > spentEnd) {
        canvas.save()
        canvas.clipRect(spentEnd, barTop, projectedEnd, barBottom)
        val hatch = paint(if (over) 0xB3EF5350.toInt() else (fill and 0x00FFFFFF) or 0xB3000000.toInt(), 4f)
        var x = spentEnd - h
        while (x < projectedEnd + h) {
            canvas.drawLine(x, barBottom, x + (barBottom - barTop), barTop, hatch)
            x += 11f
        }
        canvas.restore()
    }
    if (spentEnd > 0f) {
        canvas.drawRoundRect(RectF(0f, barTop, maxOf(spentEnd, r * 2), barBottom), r, r, paint(fill))
    }
    rail.limit?.let { at ->
        val x = w * at
        canvas.drawLine(x, barTop - 5f, x, barBottom + 5f, paint(0xFFEF5350.toInt(), 4f))
    }
    rail.evenPace?.let { at ->
        val x = (w * at).coerceIn(2f, w - 2f)
        canvas.drawLine(x, 2f, x, h - 2f, paint(tick, 4f))
    }
    return bitmap
}
