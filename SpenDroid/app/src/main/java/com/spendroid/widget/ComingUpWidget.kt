package com.spendroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.spendroid.MainActivity
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.ui.formatMoney
import com.spendroid.ui.payeeColour
import com.spendroid.ui.tidyPayee
import java.time.format.DateTimeFormatter

/**
 * The bills still to go out before payday, the next few as bars: each one's share of all that's
 * still to come, so the big one - usually the card - stands out. Cards in purple.
 */
class ComingUpWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    private data class Tile(val name: String, val amount: String, val date: String, val colour: Int, val card: Boolean, val share: Float = 0f)

    private data class Content(val tiles: List<Tile>, val total: String?, val accent: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = load(context)
        val accent = widgetAccent(context)
        val firstDroid = widgetDroid(context)
        provideContent {
            val droid = rememberFresh(firstDroid) { widgetDroid(context) }
            val content = rememberFresh(first) { load(context) }
            // Locked, with figures hidden: the heading and the way in, nothing else.
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetDroid provides droid) { if (!com.spendroid.data.Privacy(context).widgetFigures) LockedBody("COMING UP", accent) else Body(content) }
        }
    }

    private suspend fun load(context: Context): Content {
        val accent = widgetAccent(context)
        val snapshot = runCatching { SharedSnapshot.get(context) }.getOrNull() ?: return Content(emptyList(), null, accent)
        val today = snapshot.asOf
        val all = snapshot.upcomingFixed.sumOf { it.amountMinor }.coerceAtLeast(1L).toFloat()
        val tiles = snapshot.upcomingFixed.take(4).map { p ->
            val card = p.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
            val c = payeeColour(p.rule.payee)
            Tile(
                name = p.rule.payee.tidyPayee(),
                amount = formatMoney(p.amountMinor, p.rule.currency).replace(Regex("[.,]00\\b"), ""),
                date = (if (card) "~" else "") + when (p.dueDate) {
                    today -> "Today"
                    today.plusDays(1) -> "Tmrw"
                    else -> p.dueDate.format(DAY)
                },
                colour = if (card) 0xFF8E5CF6.toInt() else android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()),
                card = card,
                share = (p.amountMinor / all).coerceIn(0.03f, 1f),
            )
        }
        val total = snapshot.upcomingFixed.takeIf { it.isNotEmpty() }?.let { formatMoney(it.sumOf { p -> p.amountMinor }, snapshot.baseCurrency) }
        return Content(tiles, total, accent)
    }

    @Composable
    private fun Body(content: Content) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(WIDGET_CHARCOAL)))
                .cornerRadius(22.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Image(
                provider = ImageProvider(glowBitmap(content.accent, 0.26f)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
            Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    DroidTitle("coming up", TextStyle(color = ColorProvider(Color(content.accent)), fontSize = 12.sp, fontWeight = FontWeight.Bold), GlanceModifier.defaultWeight())
                    content.total?.let { Text("$it before payday", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 10.sp)) }
                }
                Spacer(GlanceModifier.height(8.dp))
                if (content.tiles.isEmpty()) {
                    Text("Nothing more before payday", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 12.sp))
                } else {
                    val size = androidx.glance.LocalSize.current
                    val room = ((size.height.value - 40f) / 30f).toInt().coerceIn(1, 4)
                    content.tiles.take(room).forEach { t -> BillRow(t, size.width.value - 24f) }
                }
            }
        }
    }

    /** A bill: its name and when, its amount, and a bar of its share of all that's to come. */
    @Composable
    private fun BillRow(t: Tile, widthDp: Float) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text("${t.name} · ${t.date}", maxLines = 1, style = TextStyle(color = ColorProvider(Color(0xFFDFE3EA)), fontSize = 11.sp), modifier = GlanceModifier.defaultWeight())
                Text(t.amount, maxLines = 1, style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
            Image(
                provider = ImageProvider(visorBitmap(widthDp.coerceAtLeast(40f), 9f, t.share, null, t.colour)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height((9f + VISOR_TICK_OVER * 2).dp),
            )
        }
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    }
}

class ComingUpWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ComingUpWidget()
}

/** Redraws the Coming up widgets from what is stored. */
suspend fun refreshComingUpWidgets(context: Context) {
    runCatching { ComingUpWidget().refreshEach(context) }
}
