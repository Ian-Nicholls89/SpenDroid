package com.spendroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.glance.GlanceId
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.compose.ui.unit.dp
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.spendroid.data.db.AccountEntity
import com.spendroid.domain.BudgetPace
import java.time.Instant
import kotlinx.coroutines.flow.first
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
    // Accounts with no bank link never sync, so they say nothing about how fresh the figures are.
    val linked = accounts.filter { !it.manual }
    val current = linked.filter { it.accountType == com.spendroid.data.db.AccountType.PERSONAL }.ifEmpty { linked }
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

/**
 * A period's line: [share] of the bar filled, from the left, in [fill], and a tick at [tick].
 * Filling up it is what has gone and how far through the period; draining down, what is left
 * and how much of the period is still to come - the same reading, turned round.
 */
fun periodBarBitmap(share: Float?, tick: Float?, fill: Int, track: Int, tickColour: Int, extra: Float = 0f): Bitmap {
    val w = 600
    val h = 36
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val top = 11f
    val bottom = 25f
    val r = 7f
    canvas.drawRoundRect(RectF(0f, top, w.toFloat(), bottom), r, r, paint(track))
    // What is known to be coming, paler, after what has gone.
    if (extra > 0f) {
        val end = w * ((share ?: 0f) + extra).coerceIn(0f, 1f)
        canvas.drawRoundRect(RectF(0f, top, maxOf(end, r * 2), bottom), r, r, paint((fill and 0x00FFFFFF) or 0x5A000000))
    }
    share?.let {
        val end = w * it.coerceIn(0f, 1f)
        if (end > 0f) canvas.drawRoundRect(RectF(0f, top, maxOf(end, r * 2), bottom), r, r, paint(fill))
    }
    tick?.let {
        val x = (w * it).coerceIn(3f, w - 3f)
        canvas.drawLine(x, 2f, x, h - 2f, paint(tickColour, 6f))
    }
    return bitmap
}

/** The charcoal every widget now sits on, as the app does. */
const val WIDGET_CHARCOAL = 0xF218191D.toInt()

/** The user's accent, as the app shows it, for a widget's wordmark and glow. */
suspend fun widgetAccent(context: Context): Int {
    val app = context.applicationContext as? com.spendroid.BudgetApplication ?: return 0xFFF5A623.toInt()
    val name = runCatching { app.repository.accent.first() }.getOrNull()
    val c = com.spendroid.ui.theme.Accent.from(name).colour
    return android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
}

/** A wash of [colour] fading downwards, behind a widget's top: the app's header glow. */
fun glowBitmap(colour: Int, strength: Float = 0.38f): Bitmap {
    val h = 96
    val bitmap = Bitmap.createBitmap(1, h, Bitmap.Config.ARGB_8888)
    for (y in 0 until h) {
        val a = (strength * 255 * (1f - y / (h - 1f))).toInt().coerceIn(0, 255)
        bitmap.setPixel(0, y, (a shl 24) or (colour and 0x00FFFFFF))
    }
    return bitmap
}

/**
 * The app's labelled bar: a thick rounded bar with its figure written inside, and a white tick
 * where an even pace would have it. Drawn at the size it is shown, three pixels to the dp, so
 * the writing is not stretched.
 */
fun labelledBarBitmap(widthDp: Float, heightDp: Float, fraction: Float, label: String, fill: Int, tick: Float?): Bitmap {
    val scale = 3f
    val w = (widthDp * scale).toInt().coerceAtLeast(30)
    val h = (heightDp * scale).toInt().coerceAtLeast(12)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val r = h / 2f
    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint(0xFF33363E.toInt()))
    val f = fraction.coerceIn(0f, 1f)
    if (f > 0f) canvas.drawRoundRect(RectF(0f, 0f, (w * f).coerceAtLeast(h.toFloat()), h.toFloat()), r, r, paint(fill))
    tick?.let { t ->
        val x = w * t.coerceIn(0f, 1f)
        canvas.drawRoundRect(RectF(x - scale * 1.5f, 0f, x + scale * 1.5f, h.toFloat()), scale, scale, paint(0xFFFFFFFF.toInt()))
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = h * 0.56f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(scale * 1.5f, 0f, 0f, 0x99000000.toInt())
    }
    canvas.drawText(label, w / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text)
    return bitmap
}

/**
 * The widgets' colours: the app's charcoal and its accent, fixed rather than taken from the
 * system, so a widget looks like the app it opens whatever the phone's wallpaper colours.
 */
fun widgetColours(accent: Int): androidx.glance.color.ColorProviders {
    val a = androidx.compose.ui.graphics.Color(accent)
    return androidx.glance.material3.ColorProviders(
        scheme = androidx.compose.material3.darkColorScheme(
            primary = a,
            onPrimary = androidx.compose.ui.graphics.Color(0xFF111111),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFF2A2C33),
            onSecondaryContainer = androidx.compose.ui.graphics.Color.White,
            surface = androidx.compose.ui.graphics.Color(WIDGET_CHARCOAL),
            onSurface = androidx.compose.ui.graphics.Color.White,
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A2C33),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9AA0AA),
            background = androidx.compose.ui.graphics.Color(WIDGET_CHARCOAL),
            onBackground = androidx.compose.ui.graphics.Color.White,
        ),
    )
}

/** Stamped on each widget at every refresh; a widget reloads its figures whenever it changes. */
internal val REFRESHED_AT = androidx.datastore.preferences.core.longPreferencesKey("refreshed_at")

/**
 * A widget's figures, loaded afresh on every refresh. Glance keeps a widget's drawing live for a
 * while after it's drawn, and an update meanwhile only redrew the figures it had already loaded -
 * so a refresh soon after a sync, or the refresh button, could show the old ones. [first] is what
 * it was drawn with; a new refresh stamp loads again.
 */
@androidx.compose.runtime.Composable
internal fun <T> rememberFresh(first: T, load: suspend () -> T): T {
    val stamp = androidx.glance.currentState(REFRESHED_AT) ?: 0L
    val drawnWith = androidx.compose.runtime.remember { stamp }
    val value = androidx.compose.runtime.produceState(first, stamp) {
        if (stamp != drawnWith) value = load()
    }
    return value.value
}

/** Stamps every widget of this kind and redraws it, so each loads its figures again. */
internal suspend fun androidx.glance.appwidget.GlanceAppWidget.refreshEach(context: Context) {
    val now = System.currentTimeMillis()
    androidx.glance.appwidget.GlanceAppWidgetManager(context).getGlanceIds(this::class.java).forEach { id ->
        runCatching {
            androidx.glance.appwidget.state.updateAppWidgetState(context, id) { it[REFRESHED_AT] = now }
            update(context, id)
        }
    }
}

/**
 * The droid for a widget, as a bitmap: widgets can't draw Compose, so this is the same drawing
 * as the app's (see ui/Droid.kt) on an Android canvas. [used] and [elapsed] are the visor's fill
 * and tick; the mood comes from [pace], and its colour is [accent] unless it's a warning.
 */
fun droidBitmap(pace: com.spendroid.domain.BudgetPace.Pace, used: Float?, elapsed: Float?, accent: Int, sizePx: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bitmap)
    c.scale(sizePx / 108f, sizePx / 108f)
    val white = android.graphics.Color.WHITE
    val colour = when (pace) {
        com.spendroid.domain.BudgetPace.Pace.TIGHT -> 0xFFF5A623.toInt()
        com.spendroid.domain.BudgetPace.Pace.OVER -> 0xFFE5534B.toInt()
        else -> accent
    }
    val line = paint(white, 4f)
    c.drawLine(62f, 25f, 67f, 15f, line)
    c.drawCircle(68f, 13f, 4.5f, paint(colour))
    c.drawRoundRect(android.graphics.RectF(26f, 27f, 82f, 79f), 16f, 16f, paint(white, 5f))
    c.drawRoundRect(android.graphics.RectF(33f, 40f, 75f, 54f), 4.2f, 4.2f, paint(0xFF3A3D45.toInt()))
    val fill = (used ?: 0f).coerceIn(0f, 1f)
    if (fill > 0f) c.drawRoundRect(android.graphics.RectF(33f, 40f, 33f + 42f * fill, 54f), 4.2f, 4.2f, paint(colour))
    val x = (33f + 42f * (elapsed ?: 0.5f).coerceIn(0f, 1f) - 2f).coerceIn(33f, 73f)
    c.drawRoundRect(android.graphics.RectF(x, 36f, x + 4f, 58f), 2f, 2f, paint(white))
    val mouth = android.graphics.Path()
    when (pace) {
        com.spendroid.domain.BudgetPace.Pace.ON_TRACK -> { mouth.moveTo(45f, 66f); mouth.quadTo(54f, 72f, 63f, 66f) }
        com.spendroid.domain.BudgetPace.Pace.TIGHT -> { mouth.moveTo(46f, 68f); mouth.lineTo(62f, 68f) }
        com.spendroid.domain.BudgetPace.Pace.OVER -> { mouth.moveTo(45f, 70f); mouth.quadTo(54f, 64f, 63f, 70f) }
    }
    c.drawPath(mouth, paint(white, 4.5f))
    return bitmap
}

/** The droid as the budget has it now, for a widget's header: its face from the shared budget. */
suspend fun widgetDroid(context: Context, sizePx: Int = 72): Bitmap {
    val accent = widgetAccent(context)
    val snapshot = runCatching { SharedSnapshot.get(context) }.getOrNull()
    val pace = snapshot?.let { com.spendroid.domain.BudgetPace.of(it) } ?: com.spendroid.domain.BudgetPace.Pace.ON_TRACK
    return droidBitmap(
        pace,
        snapshot?.let { com.spendroid.domain.BudgetPace.usedFraction(it) },
        snapshot?.let { com.spendroid.domain.BudgetPace.elapsedFraction(it) },
        accent,
        sizePx,
    )
}

/** The droid every widget header shows, provided once at the top of each widget. */
internal val LocalWidgetDroid = androidx.compose.runtime.staticCompositionLocalOf<Bitmap?> { null }

/** A widget's title with the droid in front of it, as the app's headers have it. */
@androidx.compose.runtime.Composable
internal fun DroidTitle(title: String, style: androidx.glance.text.TextStyle, modifier: androidx.glance.GlanceModifier = androidx.glance.GlanceModifier) {
    androidx.glance.layout.Row(modifier, verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
        LocalWidgetDroid.current?.let { droid ->
            androidx.glance.Image(
                provider = androidx.glance.ImageProvider(droid),
                contentDescription = null,
                modifier = androidx.glance.GlanceModifier.size(16.dp),
            )
            androidx.glance.layout.Spacer(androidx.glance.GlanceModifier.width(5.dp))
        }
        androidx.glance.text.Text(title, style = style)
    }
}
