package com.spendroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.data.MoneyWatch
import com.spendroid.data.Privacy
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter

/**
 * Where the budget's account is heading, to payday: a bar for now and for each day money moves,
 * the balance as a share of the most it'll hold - amber where it runs low, red below zero.
 */
class AheadWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    /** One date's balance: its share of the most the account holds before payday. */
    private data class Point(val label: String, val share: Float, val amount: String, val low: Boolean, val below: Boolean)

    private data class Content(val points: List<Point>, val until: String?, val accent: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accent = widgetAccent(context)
        val first = load(context, accent)
        val firstDroid = widgetDroid(context)
        provideContent {
            val droid = rememberFresh(firstDroid) { widgetDroid(context) }
            val content = rememberFresh(first) { load(context, widgetAccent(context)) }
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetDroid provides droid) { if (!Privacy(context).widgetFigures) LockedBody("AHEAD", content.accent) else Body(content) }
        }
    }

    private suspend fun load(context: Context, accent: Int): Content {
        val app = context.applicationContext as? BudgetApplication ?: return Content(emptyList(), null, accent)
        val snapshot = runCatching { SharedSnapshot.get(context) }.getOrNull()
        val outlook = runCatching {
            app.repository.outlook(snapshot, app.repository.transactions(), MoneyWatch(context).includeVariable)
        }.getOrNull()
        val ahead = outlook?.ahead ?: return Content(emptyList(), null, accent)
        val currency = snapshot?.baseCurrency ?: "GBP"
        val payday = outlook.payday
        val low = ahead.lowestBefore(payday)
        // Now, then each day money moves, up to and including payday; at most five, and never
        // leaving out the lowest point.
        val moving = ahead.days.drop(1).filter { d -> d.events.isNotEmpty() && (payday == null || !d.date.isAfter(payday)) }
        val chosen = (moving.take(4) + listOfNotNull(low.takeIf { it !in moving.take(4) && it != ahead.days.first() }))
            .distinct().sortedBy { it.date }.takeLast(4)
        val most = (listOf(ahead.startMinor) + chosen.map { it.balanceMinor }).max().coerceAtLeast(1L).toFloat()
        val lowLine = most * 0.25f
        fun point(label: String, balance: Long) =
            Point(label, (balance / most).coerceIn(0f, 1f), formatMoney(balance, currency), balance < lowLine, balance < 0)
        return Content(
            points = listOf(point("now", ahead.startMinor)) + chosen.map { point(it.date.format(DAY), it.balanceMinor) },
            until = payday?.let { "to ${it.format(DAY)}" },
            accent = accent,
        )
    }

    @Composable
    private fun Body(c: Content) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(WIDGET_CHARCOAL))).cornerRadius(22.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Image(ImageProvider(glowBitmap(c.accent, 0.26f)), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
            Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    DroidTitle("ahead", TextStyle(color = ColorProvider(Color(c.accent)), fontSize = 12.sp, fontWeight = FontWeight.Bold), GlanceModifier.defaultWeight())
                    c.until?.let { Text(it, style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 10.sp)) }
                }
                Spacer(GlanceModifier.height(6.dp))
                if (c.points.isEmpty()) {
                    Text("Nothing to forecast yet", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 12.sp))
                } else {
                    // A bar per date, each the balance's share of the most it holds.
                    val barWidth = androidx.glance.LocalSize.current.width.value - 24f - 48f - 60f
                    c.points.forEach { p ->
                        Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                            Text(p.label, style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 10.sp), maxLines = 1, modifier = GlanceModifier.width(44.dp))
                            Image(
                                ImageProvider(visorBitmap(barWidth.coerceAtLeast(40f), 10f, p.share, null, if (p.below) 0xFFE5534B.toInt() else if (p.low) 0xFFF5A623.toInt() else c.accent)),
                                contentDescription = "${p.label} ${p.amount}",
                                contentScale = ContentScale.FillBounds,
                                modifier = GlanceModifier.defaultWeight().height((10f + VISOR_TICK_OVER * 2).dp),
                            )
                            Text(p.amount, style = TextStyle(color = ColorProvider(Color(if (p.below) 0xFFE5534B else 0xFFFFFFFF)), fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.width(60.dp).padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    }
}

/** The forecast line for a widget: the balance each day, a dot where money moves. */
internal fun forecastBitmap(balances: List<Long>, moves: List<Long>, colour: Int): Bitmap {
    val w = 600; val h = 160; val pad = 10f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (balances.size < 2) return bmp
    val c = Canvas(bmp)
    val lo = minOf(balances.min(), 0L).let { if (balances.min() > 0) balances.min() else it }
    val hi = balances.max()
    val span = (hi - lo).coerceAtLeast(1L).toFloat()
    fun x(i: Int) = pad + (w - pad * 2) * i / (balances.size - 1)
    fun y(v: Long) = pad + (h - pad * 2) * (1f - (v - lo) / span)
    if (balances.min() < 0) {
        c.drawLine(0f, y(0), w.toFloat(), y(0), Paint().apply { this.color = 0xAAE5534B.toInt(); strokeWidth = 3f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) })
    }
    val path = Path().apply { balances.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = colour; style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND })
    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    moves.forEachIndexed { i, m ->
        if (m == 0L) return@forEachIndexed
        dot.color = if (m > 0) 0xFF5FD38A.toInt() else 0xFFF5A623.toInt()
        c.drawCircle(x(i), y(balances[i]), 8f, dot)
    }
    return bmp
}

/** A widget while SpenDroid is locked and figures are hidden: its heading, "£•••", and the way in. */
@Composable
internal fun LockedBody(title: String, accent: Int) {
    Box(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(WIDGET_CHARCOAL))).cornerRadius(22.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            // Figures hidden, so the droid's visor - the budget used - is hidden too: a plain droid.
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetDroid provides droidBitmap(com.spendroid.domain.BudgetPace.Pace.ON_TRACK, null, null, accent, 72)) {
                DroidTitle(title.lowercase(), TextStyle(color = ColorProvider(Color(accent)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(Privacy.HIDDEN, style = TextStyle(color = ColorProvider(Color.White), fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Text("Locked · tap to open", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 11.sp))
            Spacer(GlanceModifier.height(8.dp))
            // An empty visor: the shape of the read, none of the figures.
            Image(ImageProvider(visorBitmap(140f, 12f, 0f, null, accent)), contentDescription = null, modifier = GlanceModifier.width(140.dp).height((12f + 2 * VISOR_TICK_OVER).dp))
        }
    }
}

class AheadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AheadWidget()
}

suspend fun refreshAheadWidgets(context: Context) {
    runCatching { AheadWidget().refreshEach(context) }
}
