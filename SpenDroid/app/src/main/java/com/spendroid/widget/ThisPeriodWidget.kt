package com.spendroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
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
import com.spendroid.data.db.AccountType
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.PeriodRows
import com.spendroid.ui.formatMoney

/**
 * "This period": up to three accounts, each with what has gone out this period - the pay cycle
 * for a current or joint account, the statement for a card - a line against the budget or the
 * usual, and a tick for how far through the period today is. Line past the tick: spending faster
 * than the days. The lines fill up by default; a widget can be set to drain down instead.
 */
class ThisPeriodWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val content = load(context, widgetId)
        provideContent { Body(content) }
    }

    private data class Content(val rows: List<PeriodRows.Row>, val drain: Boolean, val updated: String?)

    private suspend fun load(context: Context, widgetId: Int): Content? {
        val app = context.applicationContext as? BudgetApplication ?: return null
        return runCatching {
            val snapshot = SharedSnapshot.get(context) ?: return Content(emptyList(), false, null)
            val accounts = app.repository.accounts()
            // Not chosen yet: the budget's account and the first card, the likeliest pair.
            val choice = ThisPeriodPrefs.load(context, widgetId) ?: ThisPeriodPrefs.Choice(
                listOfNotNull(
                    snapshot.potAccountId ?: accounts.firstOrNull { it.accountType == AccountType.PERSONAL }?.id,
                    accounts.firstOrNull { it.accountType == AccountType.CREDIT_CARD }?.id,
                ),
                drain = false,
            )
            Content(
                PeriodRows.rows(choice.accountIds, snapshot, accounts, app.repository.transactions()),
                choice.drain,
                updatedLabel(accounts),
            )
        }.getOrNull()
    }

    @Composable
    private fun Body(content: Content?) {
        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(18.dp)
                    .padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                when {
                    content == null -> Note("Open SpenDroid")
                    content.rows.isEmpty() -> Note("Long-press to choose accounts")
                    else -> Rows(content)
                }
            }
        }
    }

    @Composable
    private fun Note(text: String) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }

    @Composable
    private fun Rows(content: Content) {
        val size = LocalSize.current
        // Each row needs about 58dp; fewer are shown rather than squeezed.
        val room = ((size.height - 44.dp) / 58.dp).toInt().coerceIn(1, ThisPeriodPrefs.MAX_ACCOUNTS)
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(
                    "This period",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                content.updated?.let { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp)) }
                Text(
                    "↻",
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>()).padding(start = 6.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
            content.rows.take(room).forEach { row ->
                Spacer(GlanceModifier.height(8.dp))
                RowView(row, content.drain)
            }
        }
    }

    @Composable
    private fun RowView(row: PeriodRows.Row, drain: Boolean) {
        val colour = paceColour(row.pace)
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                row.label,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                formatMoney(row.spentMinor, row.currency),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                kindLabel(row.kind),
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
            )
            againstLabel(row)?.let { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp)) }
        }
        val share = if (drain) row.used?.let { 1f - it } else row.used
        val tick = if (drain) row.gone?.let { 1f - it } else row.gone
        Image(
            provider = ImageProvider(periodBarBitmap(share, tick, fill = colour, track = TRACK, tickColour = TICK)),
            contentDescription = spoken(row),
            modifier = GlanceModifier.fillMaxWidth().height(9.dp),
            contentScale = ContentScale.FillBounds,
        )
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                caption(row),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = if (row.pace == BudgetPace.Pace.ON_TRACK) GlanceTheme.colors.onSurfaceVariant else ColorProvider(androidx.compose.ui.graphics.Color(colour)),
                    fontSize = 9.sp,
                ),
            )
            if (row.pendingMinor > 0L) {
                Text(
                    "incl. ${formatMoney(row.pendingMinor, row.currency)} pending",
                    style = TextStyle(color = ColorProvider(androidx.compose.ui.graphics.Color(PENDING)), fontSize = 9.sp),
                )
            }
        }
    }

    companion object {
        private const val TRACK = 0x40808080
        private const val TICK = 0xFFE8EDF5.toInt()
        private const val GREEN = 0xFF66BB6A.toInt()
        private const val AMBER = 0xFFFFB74D.toInt()
        private const val RED = 0xFFEF5350.toInt()
        private const val PENDING = 0xFFFFD696.toInt()

        fun paceColour(pace: BudgetPace.Pace): Int = when (pace) {
            BudgetPace.Pace.ON_TRACK -> GREEN
            BudgetPace.Pace.TIGHT -> AMBER
            BudgetPace.Pace.OVER -> RED
        }

        fun kindLabel(kind: PeriodRows.Kind): String = when (kind) {
            PeriodRows.Kind.BUDGET -> "Budget · pay cycle"
            PeriodRows.Kind.CYCLE -> "Pay cycle"
            PeriodRows.Kind.STATEMENT -> "Statement"
        }

        fun againstLabel(row: PeriodRows.Row): String? {
            val against = row.againstMinor ?: return null
            val money = formatMoney(against, row.currency)
            return when (row.against) {
                PeriodRows.Against.BUDGET -> "of $money"
                PeriodRows.Against.LIMIT -> "your limit $money"
                PeriodRows.Against.USUAL -> if (row.kind == PeriodRows.Kind.STATEMENT) "usual bill $money" else "usual $money"
                null -> null
            }
        }

        fun caption(row: PeriodRows.Row): String {
            val day = if (row.day != null && row.days != null) "day ${row.day} of ${row.days}" else null
            val lead = when (row.pace) {
                BudgetPace.Pace.OVER -> row.projectedMinor?.takeIf { row.kind == PeriodRows.Kind.STATEMENT }
                    ?.let { "▲ Heading for ~${formatMoney(it, row.currency)}" } ?: "▲ Ahead of the days"
                BudgetPace.Pace.TIGHT -> "◆ A little ahead of the days"
                BudgetPace.Pace.ON_TRACK -> "● On track"
            }
            return listOfNotNull(lead, day).joinToString(" · ")
        }

        private fun spoken(row: PeriodRows.Row): String =
            "${row.label}: ${formatMoney(row.spentMinor, row.currency)} ${againstLabel(row).orEmpty()}, ${caption(row)}"
    }
}

class ThisPeriodWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThisPeriodWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { ThisPeriodPrefs.forget(context, it) }
    }
}

internal suspend fun refreshPeriodWidgets(context: Context) {
    runCatching { ThisPeriodWidget().updateAll(context) }
}
