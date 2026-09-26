package com.spendroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.ui.formatMoney
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The days until payday as a line, with a dot on each day a bill leaves.
 *
 * Answers "what is coming out, and when" without opening the app - the question the balance
 * widget's list of lines answers in words, here answered in position, so a cluster of bills
 * the day before payday looks like one.
 */
class PaydayTimelineWidget : GlanceAppWidget() {

    // Exact, so widths drawn from the size match the widget as it really is; see the balance widget.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val timeline = load(context)
        provideContent { Body(timeline) }
    }

    private data class Timeline(
        val daysLeft: Int,
        val today: Float,
        val bills: List<Pair<Float, Boolean>>,
        val spoken: String,
        val billsTotal: String,
        val left: String,
        val payday: String,
        val pace: BudgetPace.Pace,
        val updated: String?,
    )

    private suspend fun load(context: Context): Timeline? {
        val app = context.applicationContext as? BudgetApplication ?: return null
        return runCatching {
            val snapshot = app.repository.budgetSnapshot() ?: return null
            val payday = snapshot.nextIncomeDate ?: return null
            val start = snapshot.cycleStart
            val today = snapshot.asOf
            val due = snapshot.upcomingFixed
            Timeline(
                daysLeft = snapshot.daysUntilNextIncome ?: 0,
                today = WidgetGeometry.fraction(start, payday, today),
                bills = due.map { payment ->
                    WidgetGeometry.fraction(start, payday, payment.dueDate) to
                        payment.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                },
                spoken = due.joinToString("; ") { payment ->
                    "${payment.rule.payee}, ${formatMoney(payment.amountMinor, payment.rule.currency)}, " +
                        payment.dueDate.format(DAY_FORMAT)
                },
                billsTotal = formatMoney(due.sumOf { it.amountMinor }, snapshot.baseCurrency),
                left = formatMoney(snapshot.availableToSpend, snapshot.baseCurrency),
                payday = payday.format(DAY_FORMAT),
                pace = BudgetPace.of(snapshot),
                updated = updatedLabel(app.repository.accounts()),
            )
        }.getOrNull()
    }

    @Composable
    private fun Body(timeline: Timeline?) {
        val background = when (timeline?.pace) {
            BudgetPace.Pace.OVER -> RedDeep
            BudgetPace.Pace.TIGHT -> AmberDeep
            else -> GreenDeep
        }
        // A one-row widget can be as little as 50dp tall, so it gives up most of its margin.
        val tall = LocalSize.current.height >= STANDARD.height
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(background)
                .cornerRadius(18.dp)
                .padding(horizontal = 14.dp, vertical = if (tall) 10.dp else 6.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (timeline == null) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Appears once an income has been detected",
                        style = text(11.sp).copy(textAlign = TextAlign.Center),
                    )
                }
            } else {
                Content(timeline)
            }
        }
    }

    @Composable
    private fun Content(timeline: Timeline) {
        val tall = LocalSize.current.height >= STANDARD.height
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(
                    "Until payday · ${timeline.daysLeft} day${if (timeline.daysLeft == 1) "" else "s"}",
                    style = text(11.sp).copy(fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(paceLabel(timeline.pace), style = text(10.sp).copy(fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(if (tall) 8.dp else 2.dp))
            Image(
                // Drawn at the shape it is shown at, so the line runs the full width and the dots
                // stay round instead of being fitted small into the middle.
                provider = ImageProvider(
                    timelineBitmap(
                        timeline.today,
                        timeline.bills,
                        aspect = (LocalSize.current.width.value - 28f) / (if (tall) 22f else 16f),
                    ),
                ),
                contentDescription = if (timeline.spoken.isBlank()) {
                    "No bills before payday on ${timeline.payday}"
                } else {
                    "Bills before payday on ${timeline.payday}: ${timeline.spoken}"
                },
                modifier = GlanceModifier.fillMaxWidth().height(if (tall) 22.dp else 16.dp),
            )
            if (tall) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text("today", style = text(9.sp).copy(color = ColorProvider(Muted)), modifier = GlanceModifier.defaultWeight())
                    Text("payday ${timeline.payday}", style = text(9.sp).copy(color = ColorProvider(Muted)))
                }
                Spacer(GlanceModifier.defaultWeight())
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Bottom) {
                    Text(
                        "${timeline.billsTotal} of bills before payday",
                        style = text(11.sp),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text("then ${timeline.left} is yours", style = text(11.sp).copy(fontWeight = FontWeight.Bold))
                }
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    timeline.updated?.let { Text(it, style = text(9.sp).copy(color = ColorProvider(Muted))) }
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        "↻",
                        style = text(13.sp).copy(fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>()).padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    private fun text(size: androidx.compose.ui.unit.TextUnit) = TextStyle(
        color = ColorProvider(Color.White),
        fontSize = size,
    )

    private companion object {
        val STRIP = DpSize(250.dp, 50.dp)
        val STANDARD = DpSize(250.dp, 120.dp)

        val GreenDeep = ColorProvider(Color(0xFF256B29))
        val AmberDeep = ColorProvider(Color(0xFF9A5B00))
        val RedDeep = ColorProvider(Color(0xFF8A2025))
        val Muted = Color(0xD9FFFFFF)

        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
    }
}

class PaydayTimelineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PaydayTimelineWidget()
}

internal suspend fun refreshTimelineWidgets(context: Context) {
    runCatching { PaydayTimelineWidget().updateAll(context) }
}
