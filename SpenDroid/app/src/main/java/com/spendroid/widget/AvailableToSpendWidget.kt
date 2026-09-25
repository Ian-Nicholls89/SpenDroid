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
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
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
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.visual
import kotlinx.coroutines.flow.first
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
            LARGE,
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummary(context)
        provideContent { WidgetBody(summary) }
    }

    /** A bill or a detected outgoing, flattened to what the widget actually prints. */
    private data class Line(val label: String, val amount: String, val due: String)

    /** A category's share of the cycle so far, for the dashboard's top three. */
    private data class CategoryLine(val name: String, val label: String, val amount: String, val share: Float, val color: Color)

    private data class Summary(
        val available: String,
        val availableRounded: String,
        val days: String?,
        val perDay: String?,
        val incomeDate: String?,
        val remaining: Float,
        val pace: BudgetPace.Pace,
        val lines: List<Line>,
        /** Fraction of the budget used, and of the cycle gone, for the ring and the pace tick. */
        val used: Float? = null,
        val elapsed: Float? = null,
        val spentToday: String? = null,
        val week: List<Long> = emptyList(),
        val updated: String? = null,
        val categories: List<CategoryLine> = emptyList(),
        /** The day the figures are for, which the week's bars end on. */
        val asOf: java.time.LocalDate = java.time.LocalDate.now(),
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
                .filter { it.dueMinor > 0L }
                .map { bill ->
                    // Beside a due date, the figure is what that date takes, not all that is owed.
                    Line(
                        label = bill.cardLabel,
                        amount = formatMoney(bill.dueMinor, bill.currency),
                        due = bill.dueDate?.format(DUE_FORMAT).orEmpty(),
                    )
                }
            // Card bills are listed above already; upcoming carries them too, once due.
            val fixed = snapshot.upcomingFixed
                .filterNot { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) }
                .map { payment ->
                Line(
                    label = payment.rule.payee,
                    amount = formatMoney(payment.amountMinor, payment.rule.currency),
                    due = payment.dueDate.format(DUE_FORMAT),
                )
            }

            Summary(
                available = formatMoney(snapshot.availableToSpend, snapshot.baseCurrency),
                availableRounded = poundsOnly(snapshot.availableToSpend),
                days = days?.let { "$it day${if (it == 1) "" else "s"}" },
                perDay = days?.takeIf { it > 0 }?.let {
                    "${formatMoney(snapshot.availableToSpend / it, snapshot.baseCurrency)} a day"
                },
                incomeDate = snapshot.nextIncomeDate?.let { "Income ${it.format(INCOME_FORMAT)}" },
                remaining = BudgetPace.remainingFraction(snapshot),
                pace = BudgetPace.of(snapshot),
                // Card bills first: they are the largest and the least expected.
                lines = (bills + fixed).take(4),
                used = BudgetPace.usedFraction(snapshot),
                elapsed = BudgetPace.elapsedFraction(snapshot),
                spentToday = formatMoney(snapshot.spentToday, snapshot.baseCurrency),
                week = snapshot.lastSevenDaysMinor,
                updated = updatedLabel(app.repository.accounts()),
                categories = topCategories(app, snapshot),
                asOf = snapshot.asOf,
            )
        }.getOrElse { empty("Open SpenDroid") }
    }

    /** The same breakdown the Insights tab shows for this cycle, cut to its top three. */
    private suspend fun topCategories(
        app: BudgetApplication,
        snapshot: com.spendroid.domain.BudgetSnapshot,
    ): List<CategoryLine> {
        val cycle = app.repository.transactions().filter { tx ->
            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(snapshot.cycleStart) } == true
        }
        val top = CategoryEngine.spendingBreakdown(
            cycle,
            app.repository.categoryRules.first(),
            snapshot.cardPaymentKeys,
            snapshot.creditCardAccountIds,
        ).take(3)
        val peak = top.firstOrNull()?.amountMinor?.coerceAtLeast(1L) ?: return emptyList()
        return top.map { total ->
            CategoryLine(
                name = total.category.name,
                label = total.category.label,
                amount = poundsOnly(total.amountMinor),
                share = total.amountMinor.toFloat() / peak,
                color = total.category.visual.color,
            )
        }
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
                size.height >= LARGE.height -> Large(summary, size)
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
            Rail(summary.remaining, summary.elapsed, size.width - 24.dp)
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
                    provider = ImageProvider(ringBitmap(summary.used ?: 0f, summary.elapsed)),
                    contentDescription = ringDescription(summary),
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

    /**
     * The week at a glance under the figure: a bar a day, today in full white, with today's
     * spending and how fresh the figures are beside it.
     */
    @Composable
    private fun Standard(summary: Summary, size: DpSize) {
        // Measured to fit the 110dp a 4x2 can be given: every row here has earned its height.
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            HeadRow(summary)
            Spacer(GlanceModifier.height(3.dp))
            Rail(summary.remaining, summary.elapsed, size.width - 28.dp)
            Spacer(GlanceModifier.height(5.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Bottom) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Image(
                        provider = ImageProvider(weekBitmap(summary.week)),
                        // Stretched to the row, so each bar sits over its day letter. Fitting
                        // kept the image's shape and squeezed all seven into the middle.
                        contentScale = ContentScale.FillBounds,
                        contentDescription = weekDescription(summary),
                        modifier = GlanceModifier.fillMaxWidth().height(17.dp),
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        weekLetters(summary.asOf).forEach { day ->
                            Text(
                                day,
                                style = caption(8.sp).copy(textAlign = TextAlign.Center),
                                modifier = GlanceModifier.defaultWeight(),
                            )
                        }
                    }
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(horizontalAlignment = Alignment.Horizontal.End) {
                    summary.spentToday?.let {
                        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                            Text("Today ", style = caption(10.sp))
                            Text(it, style = figure(13.sp))
                        }
                    }
                    Footer(summary)
                }
            }
        }
    }

    // ---- 4x3 -------------------------------------------------------------------------

    @Composable
    private fun Dashboard(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            HeadRow(summary)
            Spacer(GlanceModifier.height(9.dp))
            Rail(summary.remaining, summary.elapsed, size.width - 28.dp)
            Spacer(GlanceModifier.height(9.dp))
            if (summary.lines.isNotEmpty()) {
                Text("Still to come out", style = label())
                Spacer(GlanceModifier.height(4.dp))
            }
            // Three, leaving room for the footer: past that it wants scrolling, and a widget
            // that wants scrolling should have been a shortcut into the app.
            summary.lines.take(3).forEach { LineRow(it) }
            Spacer(GlanceModifier.defaultWeight())
            Footer(summary)
        }
    }

    // ---- 4x4 and up ------------------------------------------------------------------

    /**
     * The home screen's summary in one tile: the ring and figure, where the cycle's money has
     * gone, and what is still to come out. A category opens the app filtered to it.
     */
    @Composable
    private fun Large(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(
                    provider = ImageProvider(ringBitmap(summary.used ?: 0f, summary.elapsed)),
                    contentDescription = ringDescription(summary),
                    modifier = GlanceModifier.width(58.dp).height(58.dp),
                )
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text("Available to spend", style = label(), modifier = GlanceModifier.defaultWeight())
                        Text(paceLabel(summary.pace), style = chip())
                    }
                    Text(summary.available, style = figure(26.sp))
                    Text(
                        listOfNotNull(summary.days, summary.incomeDate).joinToString(" · "),
                        style = caption(11.sp),
                    )
                }
            }
            if (summary.categories.isNotEmpty()) {
                Spacer(GlanceModifier.height(12.dp))
                Text("This cycle, top categories", style = label())
                Spacer(GlanceModifier.height(5.dp))
                summary.categories.forEach { CategoryRow(it, size.width - 28.dp) }
            }
            if (summary.lines.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Text("Still to come out", style = label())
                Spacer(GlanceModifier.height(4.dp))
                summary.lines.take(2).forEach { LineRow(it) }
            }
            Spacer(GlanceModifier.defaultWeight())
            Footer(summary)
        }
    }

    @Composable
    private fun CategoryRow(line: CategoryLine, width: androidx.compose.ui.unit.Dp) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(CategoryParam to line.name))),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Box(modifier = GlanceModifier.width(8.dp).height(8.dp).cornerRadius(2.dp).background(ColorProvider(line.color))) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(line.label, maxLines = 1, style = caption(11.sp), modifier = GlanceModifier.width(82.dp))
            // The bar is a share of the largest, so the three compare at a glance.
            val track = width - 8.dp - 6.dp - 82.dp - 50.dp
            Box(
                modifier = GlanceModifier.defaultWeight().height(6.dp).cornerRadius(3.dp).background(ColorProvider(TrackSoft)),
            ) {
                Box(
                    modifier = GlanceModifier.width(track * line.share.coerceIn(0.03f, 1f)).height(6.dp)
                        .cornerRadius(3.dp).background(ColorProvider(line.color)),
                ) {}
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(line.amount, maxLines = 1, style = figure(11.sp))
        }
    }

    /** How fresh the figures are, and a way to redraw from what is stored. */
    @Composable
    private fun Footer(summary: Summary) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            summary.updated?.let { Text(it, style = caption(9.sp)) }
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "↻",
                style = figure(13.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>()).padding(horizontal = 4.dp),
            )
        }
    }

    // ---- shared pieces ---------------------------------------------------------------

    /**
     * The label with the pace beside it, then the figure with the runway beside that - two
     * short rows, so a 4x2 still has room underneath for the week.
     */
    @Composable
    private fun HeadRow(summary: Summary) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text("Available to spend", style = label(), modifier = GlanceModifier.defaultWeight())
                Text(paceLabel(summary.pace), style = chip())
            }
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(summary.available, style = figure(25.sp), modifier = GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.Horizontal.End) {
                    summary.days?.let { Text(it, style = caption(10.sp)) }
                    summary.perDay?.let { Text(it, style = caption(10.sp)) }
                }
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

    /**
     * How much of the budget is left, as a bar sized against the widget's real width, with a
     * tick where the bar would end if spending were even across the cycle. Bar short of the
     * tick is spending ahead of the days; past it is room in hand.
     */
    @Composable
    private fun Rail(remaining: Float, elapsed: Float?, available: androidx.compose.ui.unit.Dp) {
        Box(modifier = GlanceModifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
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
            elapsed?.let { gone ->
                // Where an even pace would leave the bar: the share of the cycle still to come.
                Row {
                    Spacer(GlanceModifier.width(maxOf(0.dp, available * (1f - gone).coerceIn(0f, 1f) - 1.dp)))
                    Box(modifier = GlanceModifier.width(2.dp).height(12.dp).cornerRadius(1.dp).background(ColorProvider(Color.White))) {}
                }
            }
        }
    }

    private fun ringDescription(summary: Summary): String =
        listOfNotNull(
            summary.used?.let { "${(it * 100).toInt()} percent of the budget used" },
            summary.elapsed?.let { "${(it * 100).toInt()} percent of the cycle gone" },
        ).joinToString(", ")

    private fun weekDescription(summary: Summary): String =
        "Spending over the last seven days: " +
            summary.week.joinToString(", ") { poundsOnly(it) }

    /** Initials for the last seven days, ending today. */
    private fun weekLetters(today: java.time.LocalDate): List<String> {
        return (6L downTo 0L).map { today.minusDays(it).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()) }
    }

    private fun chip() = TextStyle(
        color = ColorProvider(Color.White),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )

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
        val LARGE = DpSize(250.dp, 260.dp)

        val GreenDeep = ColorProvider(Color(0xFF256B29))
        val AmberDeep = ColorProvider(Color(0xFF9A5B00))
        val RedDeep = ColorProvider(Color(0xFF8A2025))
        val OnHeroMuted = Color(0xD9FFFFFF)
        val TrackWhite = Color(0x47FFFFFF)
        val TrackSoft = Color(0x2EFFFFFF)

        val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
        val INCOME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

        /**
         * To the pound, because on a 2x2 the pennies are noise. Stripping the decimals by
         * pattern rather than by cutting at a '.' keeps it right where the locale separates
         * with a comma.
         */
        fun poundsOnly(minor: Long): String =
            formatMoney((minor / 100L) * 100L, "GBP").replace(Regex("[.,]00\\b"), "")
    }
}

class AvailableToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AvailableToSpendWidget()
}

/** Refreshes every placed widget. Called after a sync, when the figures have actually moved. */
suspend fun refreshWidgets(context: Context) {
    runCatching { AvailableToSpendWidget().updateAll(context) }
    // Drawn from the same snapshot, so it moves when this one does.
    refreshTimelineWidgets(context)
}
