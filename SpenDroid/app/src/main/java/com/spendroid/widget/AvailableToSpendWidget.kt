package com.spendroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.toRecurringRule
import com.spendroid.ui.formatMoney
import kotlinx.coroutines.flow.first

/**
 * Available-to-spend on the home screen.
 *
 * Reads the local database directly rather than the running app: the widget updates on the
 * system's schedule, long after any Activity has gone.
 */
class AvailableToSpendWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummary(context)
        provideContent { WidgetBody(summary) }
    }

    private data class Summary(
        val available: String,
        val caption: String,
    )

    private suspend fun loadSummary(context: Context): Summary {
        val app = context.applicationContext as? BudgetApplication
            ?: return Summary("—", "Open SpenDroid")
        return runCatching {
            val repo = app.repository
            val transactions = repo.transactions()
            if (transactions.isEmpty()) return Summary("—", "No transactions yet")

            val ignored = repo.ignoredRules.first()
            val manual = repo.manualRules.first().mapNotNull { it.toRecurringRule() }
            val rules = (RecurringAnalyzer.analyze(transactions) + manual)
                .filter { it.key !in ignored }
            val snapshot = BudgetEngine.snapshot(transactions, rules, repo.accounts())

            val days = snapshot.daysUntilNextIncome
            val caption = when {
                days == null -> "until next income"
                days <= 0 -> "payday today"
                else -> {
                    val perDay = snapshot.availableToSpend / days
                    "$days day${if (days == 1) "" else "s"} · ${formatMoney(perDay, "GBP")}/day"
                }
            }
            Summary(formatMoney(snapshot.availableToSpend, "GBP"), caption)
        }.getOrElse { Summary("—", "Open SpenDroid") }
    }

    @Composable
    private fun WidgetBody(summary: Summary) {
        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(HeroGreen)
                    .cornerRadius(16.dp)
                    .padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    "Available to spend",
                    style = TextStyle(color = ColorProvider(OnHeroMuted)),
                )
                Text(
                    summary.available,
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    summary.caption,
                    style = TextStyle(color = ColorProvider(OnHeroMuted)),
                )
            }
        }
    }

    private companion object {
        // The hero gradient's start colour; a widget cannot carry a gradient.
        val HeroGreen = Color(0xFF2E7D32)
        val OnHeroMuted = Color(0xD9FFFFFF)
    }
}

class AvailableToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AvailableToSpendWidget()
}
