package com.spendroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
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
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter

/**
 * Available-to-spend on the home screen, at whatever size it was dropped in at.
 *
 * Reads the local database directly rather than the running app: the widget updates on the
 * system's schedule, long after any Activity has gone.
 *
 * One provider covers every size through [SizeMode.Responsive]. The system picks the closest
 * declared size and the layout branches on it, so resizing a placed widget re-lays it out
 * instead of refusing - which six separate providers could not do.
 */
class AvailableToSpendWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            CORNER,
            HERO,
            STRIP,
            DIAL,
            STANDARD,
            DASHBOARD,
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummary(context)
        provideContent { WidgetBody(summary) }
    }

    /** A bill or a detected outgoing, flattened to what the widget actually prints. */
    private data class Line(val label: String, val amount: String, val due: String)

    private data class Summary(
        val available: String,
        val availableRounded: String,
        val days: String?,
        val perDay: String?,
        val incomeDate: String?,
        val remaining: Float,
        val pace: BudgetPace.Pace,
        val lines: List<Line>,
        val empty: String? = null,
    )

    private suspend fun loadSummary(context: Context): Summary {
        val app = context.applicationContext as? BudgetApplication
            ?: return empty("Open SpenDroid")
        return runCatching {
            // The shared builder, so the widget cannot disagree with the home screen about
            // the budget model, the designated income or the bank holiday calendar.
            val snapshot = app.repository.budgetSnapshot() ?: return empty("No transactions yet")

            val days = snapshot.daysUntilNextIncome
            val bills = snapshot.cardBills
                .filter { it.outstandingMinor > 0L }
                .map { bill ->
                    Line(
                        label = bill.cardLabel,
                        amount = formatMoney(bill.outstandingMinor, bill.currency),
                        due = bill.dueDate?.format(DUE_FORMAT).orEmpty(),
                    )
                }
            val fixed = snapshot.upcomingFixed.map { payment ->
                Line(
                    label = payment.rule.payee,
                    amount = formatMoney(payment.amountMinor, payment.rule.currency),
                    due = payment.dueDate.format(DUE_FORMAT),
                )
            }

            Summary(
                available = formatMoney(snapshot.availableToSpend, "GBP"),
                availableRounded = poundsOnly(snapshot.availableToSpend),
                days = days?.let { "$it day${if (it == 1) "" else "s"}" },
                perDay = days?.takeIf { it > 0 }?.let {
                    "${formatMoney(snapshot.availableToSpend / it, "GBP")} a day"
                },
                incomeDate = snapshot.nextIncomeDate?.let { "Income ${it.format(INCOME_FORMAT)}" },
                remaining = BudgetPace.remainingFraction(snapshot),
                pace = BudgetPace.of(snapshot),
                // Card bills first: they are the largest and the least expected.
                lines = (bills + fixed).take(4),
            )
        }.getOrElse { empty("Open SpenDroid") }
    }

    private fun empty(message: String) = Summary(
        available = "—",
        availableRounded = "—",
        days = null,
        perDay = null,
        incomeDate = null,
        remaining = 1f,
        pace = BudgetPace.Pace.ON_TRACK,
        lines = emptyList(),
        empty = message,
    )

    @Composable
    private fun WidgetBody(summary: Summary) {
        val size = LocalSize.current
        val background = when (summary.pace) {
            BudgetPace.Pace.OVER -> RedDeep
            BudgetPace.Pace.TIGHT -> AmberDeep
            BudgetPace.Pace.ON_TRACK -> GreenDeep
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(background)
                .cornerRadius(18.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            when {
                summary.empty != null -> Centred(summary.empty)
                size.height >= DASHBOARD.height -> Dashboard(summary, size)
                size.height >= DIAL.height ->
                    if (size.width >= STANDARD.width) Standard(summary, size) else Dial(summary)
                size.width >= STRIP.width -> Strip(summary)
                size.width >= HERO.width -> Hero(summary, size)
                else -> Corner(summary)
            }
        }
    }

    // ---- 2x1 -------------------------------------------------------------------------

    /**
     * Too small for a label, so the pace moves to an edge stripe: at this size a coloured
     * background would have to fight the figure for contrast, and the figure has to win.
     */
    @Composable
    private fun Corner(summary: Summary) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Box(
                modifier = GlanceModifier
                    .width(5.dp)
                    .fillMaxSize()
                    .background(ColorProvider(Color.White)),
            ) {}
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 11.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(summary.available, style = figure(21.sp))
                summary.days?.let { Text(it, style = caption(11.sp)) }
            }
        }
    }

    // ---- 3x1 -------------------------------------------------------------------------

    @Composable
    private fun Hero(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            Text("Available to spend", style = label())
            Spacer(GlanceModifier.height(3.dp))
            Text(summary.available, style = figure(25.sp))
            Spacer(GlanceModifier.height(5.dp))
            Rail(summary.remaining, size.width - 24.dp)
            Spacer(GlanceModifier.height(4.dp))
            Text(
                listOfNotNull(summary.days, summary.perDay).joinToString(" · "),
                style = caption(11.sp),
            )
        }
    }

    // ---- 4x1 -------------------------------------------------------------------------

    @Composable
    private fun Strip(summary: Summary) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("Available to spend", style = label())
                Spacer(GlanceModifier.height(3.dp))
                Text(summary.available, style = figure(24.sp))
            }
            Column(horizontalAlignment = Alignment.Horizontal.End) {
                summary.perDay?.let { Text(it, style = caption(11.sp)) }
                summary.incomeDate?.let { Text(it, style = caption(11.sp)) }
            }
        }
    }

    // ---- 2x2 -------------------------------------------------------------------------

    /**
     * The square is the one shape where an arc beats a bar: the sweep is how much is left and
     * the figure is how much that is, in the same space a bar would need on its own.
     */
    @Composable
    private fun Dial(summary: Summary) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text("Left to spend", style = label())
            Spacer(GlanceModifier.height(6.dp))
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(arcBitmap(summary.remaining)),
                    contentDescription = "${(summary.remaining * 100).toInt()} percent of the budget left",
                    modifier = GlanceModifier.width(86.dp).height(86.dp),
                )
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    // Rounded to the pound: at this size the pennies are noise.
                    Text(summary.availableRounded, style = figure(19.sp))
                    summary.days?.let { Text(it, style = caption(10.sp)) }
                }
            }
        }
    }

    // ---- 4x2 -------------------------------------------------------------------------

    @Composable
    private fun Standard(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            HeadRow(summary)
            Spacer(GlanceModifier.height(9.dp))
            Rail(summary.remaining, size.width - 28.dp)
            Spacer(GlanceModifier.height(8.dp))
            summary.lines.take(2).forEach { LineRow(it) }
        }
    }

    // ---- 4x3 -------------------------------------------------------------------------

    @Composable
    private fun Dashboard(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            HeadRow(summary)
            Spacer(GlanceModifier.height(9.dp))
            Rail(summary.remaining, size.width - 28.dp)
            Spacer(GlanceModifier.height(9.dp))
            if (summary.lines.isNotEmpty()) {
                Text("Still to come out", style = label())
                Spacer(GlanceModifier.height(4.dp))
            }
            // Four is the honest limit: past that it wants scrolling, and a widget that wants
            // scrolling should have been a shortcut into the app.
            summary.lines.take(4).forEach { LineRow(it) }
        }
    }

    // ---- shared pieces ---------------------------------------------------------------

    @Composable
    private fun HeadRow(summary: Summary) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("Available to spend", style = label())
                Spacer(GlanceModifier.height(4.dp))
                Text(summary.available, style = figure(29.sp))
            }
            Column(horizontalAlignment = Alignment.Horizontal.End) {
                summary.days?.let { Text(it, style = caption(11.sp)) }
                summary.perDay?.let { Text(it, style = caption(11.sp)) }
            }
        }
    }

    @Composable
    private fun LineRow(line: Line) {
        Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(
                line.label,
                maxLines = 1,
                style = caption(11.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                listOf(line.amount, line.due).filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    /** How much of the budget is left, as a bar sized against the widget's real width. */
    @Composable
    private fun Rail(remaining: Float, available: androidx.compose.ui.unit.Dp) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(4.dp)
                .cornerRadius(2.dp)
                .background(ColorProvider(TrackWhite)),
        ) {
            Box(
                modifier = GlanceModifier
                    .width(available * remaining.coerceIn(0.02f, 1f))
                    .height(4.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(Color.White)),
            ) {}
        }
    }

    @Composable
    private fun Centred(message: String) {
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(message, style = caption(12.sp).copy(textAlign = TextAlign.Center))
        }
    }

    private fun figure(size: androidx.compose.ui.unit.TextUnit) = TextStyle(
        color = ColorProvider(Color.White),
        fontSize = size,
        fontWeight = FontWeight.Bold,
    )

    private fun label() = TextStyle(
        color = ColorProvider(OnHeroMuted),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )

    private fun caption(size: androidx.compose.ui.unit.TextUnit) = TextStyle(
        color = ColorProvider(OnHeroMuted),
        fontSize = size,
    )

    private companion object {
        /**
         * Declared sizes, in the dp a launcher cell actually works out at (70n - 30). The
         * system picks the largest that fits, so these double as the layout's breakpoints.
         */
        val CORNER = DpSize(110.dp, 50.dp)
        val HERO = DpSize(180.dp, 50.dp)
        val STRIP = DpSize(250.dp, 50.dp)
        val DIAL = DpSize(110.dp, 120.dp)
        val STANDARD = DpSize(250.dp, 120.dp)
        val DASHBOARD = DpSize(250.dp, 190.dp)

        val GreenDeep = ColorProvider(Color(0xFF256B29))
        val AmberDeep = ColorProvider(Color(0xFF9A5B00))
        val RedDeep = ColorProvider(Color(0xFF8A2025))
        val OnHeroMuted = Color(0xD9FFFFFF)
        val TrackWhite = Color(0x47FFFFFF)

        val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
        val INCOME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

        /**
         * To the pound, because on a 2x2 the pennies are noise. Stripping the decimals by
         * pattern rather than by cutting at a '.' keeps it right where the locale separates
         * with a comma.
         */
        fun poundsOnly(minor: Long): String =
            formatMoney((minor / 100L) * 100L, "GBP").replace(Regex("[.,]00\\b"), "")

        /**
         * Glance has no canvas, so the arc is drawn once into a bitmap and shown as an image.
         */
        fun arcBitmap(remaining: Float): Bitmap {
            val px = 220
            val stroke = 22f
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val box = RectF(stroke / 2f, stroke / 2f, px - stroke / 2f, px - stroke / 2f)

            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = 0x42FFFFFF
            }
            canvas.drawArc(box, 0f, 360f, false, track)

            val sweep = 360f * remaining.coerceIn(0f, 1f)
            if (sweep > 0f) {
                val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    strokeCap = Paint.Cap.ROUND
                    color = 0xFFFFFFFF.toInt()
                }
                canvas.drawArc(box, -90f, sweep, false, arc)
            }
            return bitmap
        }
    }
}

class AvailableToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AvailableToSpendWidget()
}

/** Refreshes every placed widget. Called after a sync, when the figures have actually moved. */
suspend fun refreshWidgets(context: Context) {
    runCatching { AvailableToSpendWidget().updateAll(context) }
}
