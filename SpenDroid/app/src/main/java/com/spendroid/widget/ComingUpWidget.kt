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
 * The bills still to go out before payday, the next four as posters - nzb360's upcoming films,
 * here the gym, the energy, the card. Each tile's chip says when.
 */
class ComingUpWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    private data class Tile(val name: String, val amount: String, val date: String, val colour: Int, val card: Boolean)

    private data class Content(val tiles: List<Tile>, val total: String?, val accent: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Locked, with figures hidden: the heading and the way in, nothing else.
        if (!com.spendroid.data.Privacy(context).widgetFigures) {
            val accent = widgetAccent(context)
            provideContent { LockedBody("COMING UP", accent) }
            return
        }
        val content = load(context)
        provideContent { Body(content) }
    }

    private suspend fun load(context: Context): Content {
        val accent = widgetAccent(context)
        val snapshot = runCatching { SharedSnapshot.get(context) }.getOrNull() ?: return Content(emptyList(), null, accent)
        val today = snapshot.asOf
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
                colour = android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()),
                card = card,
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
                    Text("COMING UP", style = TextStyle(color = ColorProvider(Color(content.accent)), fontSize = 11.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                    content.total?.let { Text("$it before payday", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 10.sp)) }
                }
                Spacer(GlanceModifier.height(8.dp))
                if (content.tiles.isEmpty()) {
                    Text("Nothing more before payday", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 12.sp))
                } else {
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        content.tiles.forEachIndexed { i, t ->
                            if (i > 0) Spacer(GlanceModifier.width(8.dp))
                            Poster(t, GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Poster(t: Tile, modifier: GlanceModifier) {
        Column(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(10.dp).background(ColorProvider(Color(t.colour))).padding(6.dp),
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        t.date,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.background(ColorProvider(Color(0x73000000))).cornerRadius(5.dp).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.Bottom) {
                    Text(
                        if (t.card) "▭" else (t.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "£"),
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
            Spacer(GlanceModifier.height(3.dp))
            Text(t.name, maxLines = 1, style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold))
            Text(t.amount, maxLines = 1, style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 9.sp))
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
    runCatching { ComingUpWidget().updateAll(context) }
}
