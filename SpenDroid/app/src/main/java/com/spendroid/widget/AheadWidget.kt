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
 * Where the budget's account is heading, to payday: the forecast line, what's in it now, and its
 * lowest point - amber, or red if that's below zero.
 */
class AheadWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    private data class Content(val line: Bitmap?, val now: String?, val lowest: String?, val lowestNote: String?, val below: Boolean, val until: String?, val accent: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accent = widgetAccent(context)
        if (!Privacy(context).widgetFigures) {
            provideContent { LockedBody("AHEAD", accent) }
            return
        }
        val content = load(context, accent)
        provideContent { Body(content) }
    }

    private suspend fun load(context: Context, accent: Int): Content {
        val app = context.applicationContext as? BudgetApplication ?: return Content(null, null, null, null, false, null, accent)
        val snapshot = runCatching { SharedSnapshot.get(context) }.getOrNull()
        val outlook = runCatching {
            app.repository.outlook(snapshot, app.repository.transactions(), MoneyWatch(context).includeVariable)
        }.getOrNull()
        val ahead = outlook?.ahead ?: return Content(null, null, null, null, false, null, accent)
        val currency = snapshot?.baseCurrency ?: "GBP"
        val low = ahead.lowestBefore(outlook.payday)
        return Content(
            line = forecastBitmap(ahead.days.map { it.balanceMinor }, ahead.days.map { d -> d.events.sumOf { it.amountMinor } }, accent),
            now = formatMoney(ahead.startMinor, currency),
            lowest = formatMoney(low.balanceMinor, currency),
            lowestNote = "lowest, ${low.date.format(DAY)}",
            below = low.balanceMinor < 0,
            until = outlook.payday?.let { "to ${it.format(DAY)}" },
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
                    Text("AHEAD", style = TextStyle(color = ColorProvider(Color(c.accent)), fontSize = 11.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                    c.until?.let { Text(it, style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 10.sp)) }
                }
                Spacer(GlanceModifier.height(6.dp))
                if (c.line == null) {
                    Text("Nothing to forecast yet", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 12.sp))
                } else {
                    Image(ImageProvider(c.line), contentDescription = "Balance ahead", contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxWidth().defaultWeight())
                    Spacer(GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text("${c.now} ", style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        Text("now", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 11.sp), modifier = GlanceModifier.defaultWeight())
                        Text("${c.lowest} ", style = TextStyle(color = ColorProvider(Color(if (c.below) 0xFFE5534B else 0xFFF5A623)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        Text(c.lowestNote.orEmpty(), style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 11.sp))
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
            Text(title, style = TextStyle(color = ColorProvider(Color(accent)), fontSize = 11.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            Text(Privacy.HIDDEN, style = TextStyle(color = ColorProvider(Color.White), fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Text("Locked · tap to open", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 11.sp))
        }
    }
}

class AheadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AheadWidget()
}

suspend fun refreshAheadWidgets(context: Context) {
    runCatching { AheadWidget().updateAll(context) }
}
