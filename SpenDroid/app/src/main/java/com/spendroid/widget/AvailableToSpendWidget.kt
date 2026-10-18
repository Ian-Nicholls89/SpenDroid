package com.spendroid.widget

import com.spendroid.ui.tidyPayee
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
 * One provider covers every size: it is told its exact size and the layout branches on it, so
 * resizing a placed widget re-lays it out instead of refusing - which six separate providers
 * could not do.
 */
class AvailableToSpendWidget : GlanceAppWidget() {

    // Exact rather than Responsive: Responsive reports the nearest declared size, not the real
    // one, so on a widget stretched past the widest declared size every width worked out from
    // it came up short - the bar and its pace tick drawn at three quarters of where they
    // belonged, against a track that spanned the whole widget. The declared sizes below remain
    // as the layout's breakpoints.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadSummary(context)
        val accent = widgetAccent(context)
        provideContent {
            val summary = rememberFresh(first) { loadSummary(context) }
            // Locked, with figures hidden: the heading and the way in, nothing else.
            if (!com.spendroid.data.Privacy(context).widgetFigures) LockedBody("LEFT TO SPEND", accent) else WidgetBody(summary)
        }
    }

    /** A bill or a detected outgoing, flattened to what the widget actually prints. */
    private data class Line(val label: String, val amount: String, val due: String)

    /** A category's share of the cycle so far, for the dashboard's top three. */
    private data class CategoryLine(val name: String, val label: String, val amount: String, val share: Float, val color: Color)

    private data class Summary(
        /** The user's accent, for the wordmark and glow. */
        val accent: Int = 0xFFF5A623.toInt(),
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
        /** Everything spent today, cards included. */
        val spentToday: String? = null,
        /** Whether some of it is still pending, which the figure says. */
        val spentTodayPending: Boolean = false,
        val week: List<Long> = emptyList(),
        val updated: String? = null,
        val categories: List<CategoryLine> = emptyList(),
        /** "day 15 of 28", for the header. */
        val dayLine: String? = null,
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
            val snapshot = SharedSnapshot.get(context) ?: return empty("No transactions yet")

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
                    label = payment.rule.payee.tidyPayee(),
                    amount = formatMoney(payment.amountMinor, payment.rule.currency),
                    due = payment.dueDate.format(DUE_FORMAT),
                )
            }

            Summary(
                accent = widgetAccent(context),
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
                spentToday = formatMoney(
                    snapshot.spentTodayFromAccountsMinor + snapshot.spentTodayOnCardsMinor,
                    snapshot.baseCurrency,
                ),
                spentTodayPending = snapshot.spentTodayPendingMinor > 0L,
                week = snapshot.lastSevenDaysMinor,
                updated = updatedLabel(app.repository.accounts()),
                categories = topCategories(app, snapshot),
                asOf = snapshot.asOf,
                dayLine = snapshot.cycleEnd?.let { end ->
                    val total = java.time.temporal.ChronoUnit.DAYS.between(snapshot.cycleStart, end).toInt() + 1
                    val day = java.time.temporal.ChronoUnit.DAYS.between(snapshot.cycleStart, snapshot.asOf).toInt() + 1
                    if (day in 1..total) "day $day of $total" else null
                },
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
        // Charcoal with the accent's glow at the top, as the app; the pace has its own chip.
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(WIDGET_CHARCOAL)))
                .cornerRadius(22.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Image(
                provider = ImageProvider(glowBitmap(summary.accent)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
            when {
                summary.empty != null -> Centred(summary.empty)
                size.height >= LARGE.height -> Large(summary, size)
                size.height >= DASHBOARD.height -> Dashboard(summary, size)
                size.height >= DIAL.height ->
                    if (size.width >= STANDARD.width) Standard(summary, size) else Dial(summary)
                size.width >= HERO.width -> Strip(summary)
                else -> Corner(summary)
            }
        }
    }

    // ---- 2x1 -------------------------------------------------------------------------

    /** The droid, wearing the pace, and the figure. */
    @Composable
    private fun Corner(summary: Summary) {
        Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Droid(summary, 28.dp)
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Figure(summary.availableRounded, 20f)
                summary.days?.let { Text(it, style = caption(10.sp)) }
            }
        }
    }

    // ---- 3x1 and 4x1 -----------------------------------------------------------------

    /** The droid and the visor, the figure at its end: the whole widget is the bar. */
    @Composable
    private fun Strip(summary: Summary) {
        val size = LocalSize.current
        Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Droid(summary, 32.dp)
            Spacer(GlanceModifier.width(10.dp))
            Visor(summary, size.width.value - 24f - 42f, 28f, summary.available)
        }
    }

    // ---- 2x2 -------------------------------------------------------------------------

    @Composable
    private fun Dial(summary: Summary) {
        val size = LocalSize.current
        Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            Head(summary, summary.days)
            Spacer(GlanceModifier.height(4.dp))
            Figure(summary.availableRounded, 28f)
            Spacer(GlanceModifier.height(4.dp))
            Visor(summary, size.width.value - 24f, 26f, null)
            Spacer(GlanceModifier.height(4.dp))
            Text(listOfNotNull(summary.perDay, summary.used?.let { "${(it * 100).toInt()}% used" }).joinToString(" · "), style = caption(10.sp), maxLines = 1)
        }
    }

    // ---- 4x2 -------------------------------------------------------------------------

    /** The visor is the widget: the figure inside it, what it means underneath. */
    @Composable
    private fun Standard(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Head(summary, summary.dayLine)
            Spacer(GlanceModifier.height(6.dp))
            Visor(summary, size.width.value - 28f, 44f, summary.available)
            Spacer(GlanceModifier.height(4.dp))
            Meaning(summary)
        }
    }

    // ---- 4x3 -------------------------------------------------------------------------

    @Composable
    private fun Dashboard(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            Head(summary, summary.dayLine)
            Spacer(GlanceModifier.height(8.dp))
            Visor(summary, size.width.value - 28f, 44f, summary.available)
            Spacer(GlanceModifier.height(4.dp))
            Meaning(summary)
            if (summary.lines.isNotEmpty()) {
                Spacer(GlanceModifier.height(8.dp))
                Text("still to come out", style = label())
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
     * The home screen's summary in one tile: the visor and figure, the week, and where the
     * cycle's money has gone, each category its own bar. A category opens the app filtered to it.
     */
    @Composable
    private fun Large(summary: Summary, size: DpSize) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            Head(summary, summary.dayLine)
            Spacer(GlanceModifier.height(8.dp))
            Visor(summary, size.width.value - 28f, 48f, summary.available)
            Spacer(GlanceModifier.height(4.dp))
            Meaning(summary)
            if (summary.week.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Text("this week", style = label())
                Spacer(GlanceModifier.height(3.dp))
                Image(
                    provider = ImageProvider(weekBitmap(summary.week)),
                    contentScale = ContentScale.FillBounds,
                    contentDescription = weekDescription(summary),
                    modifier = GlanceModifier.fillMaxWidth().height(26.dp),
                )
            }
            if (summary.categories.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Text("most spent", style = label())
                summary.categories.forEach { CategoryRow(it, size.width - 28.dp) }
            }
            Spacer(GlanceModifier.defaultWeight())
            Footer(summary)
        }
    }

    /** A category as the app's bars: its name and amount over a visor in its colour, a share of the largest. */
    @Composable
    private fun CategoryRow(line: CategoryLine, width: androidx.compose.ui.unit.Dp) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(CategoryParam to line.name))),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(line.label, maxLines = 1, style = caption(11.sp), modifier = GlanceModifier.defaultWeight())
                Text(line.amount, maxLines = 1, style = caption(11.sp))
            }
            val colour = android.graphics.Color.argb((line.color.alpha * 255).toInt(), (line.color.red * 255).toInt(), (line.color.green * 255).toInt(), (line.color.blue * 255).toInt())
            Image(
                provider = ImageProvider(visorBitmap(width.value, 9f, line.share.coerceIn(0.03f, 1f), null, colour)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height((9f + VISOR_TICK_OVER * 2).dp),
            )
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

    /** The droid, the name - "spen" white, "droid" in the app's colour - and a note on the right. */
    @Composable
    private fun Head(summary: Summary, right: String?) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Droid(summary, 18.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text("spen", style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Text("droid", style = wordmark(summary.accent), modifier = GlanceModifier.defaultWeight())
            right?.let { Text(it, style = caption(10.sp), maxLines = 1) }
        }
    }

    /** What the visor says, in words: the budget used, and the day's share and days left. */
    @Composable
    private fun Meaning(summary: Summary) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(summary.used?.let { "${(it * 100).toInt()}% of the budget used" }.orEmpty(), style = caption(10.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(listOfNotNull(summary.perDay, summary.days).joinToString(" · "), style = caption(10.sp), maxLines = 1)
        }
    }

    @Composable
    private fun Droid(summary: Summary, size: androidx.compose.ui.unit.Dp) {
        Image(
            provider = ImageProvider(droidBitmap(summary.pace, summary.used, summary.elapsed, summary.accent)),
            contentDescription = paceLabel(summary.pace),
            modifier = GlanceModifier.width(size).height(size),
        )
    }

    /**
     * The visor, as wide as it's drawn: the budget used as the fill, the tick where the days
     * are, in the app's colour while on pace and amber or red when not, [label] at its end.
     */
    @Composable
    private fun Visor(summary: Summary, widthDp: Float, heightDp: Float, label: String?) {
        val typeface = grotesk(androidx.glance.LocalContext.current)
        Image(
            provider = ImageProvider(
                visorBitmap(widthDp.coerceAtLeast(40f), heightDp, summary.used ?: 0f, summary.elapsed, visorColour(summary.pace, summary.accent), label, typeface),
            ),
            contentDescription = "${summary.available} left" + ringDescription(summary).let { if (it.isEmpty()) "" else ", $it" },
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height((heightDp + VISOR_TICK_OVER * 2).dp),
        )
    }

    /** A figure in Space Grotesk: drawn, as a widget can't use the font for text. */
    @Composable
    private fun Figure(text: String, sizeDp: Float) {
        val bitmap = figureBitmap(text, sizeDp, 0xFFFFFFFF.toInt(), grotesk(androidx.glance.LocalContext.current))
        val (w, h) = figureSizeDp(bitmap)
        Image(provider = ImageProvider(bitmap), contentDescription = text, modifier = GlanceModifier.width(w.dp).height(h.dp))
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

    private fun paceInt(pace: BudgetPace.Pace): Int = when (pace) {
        BudgetPace.Pace.ON_TRACK -> 0xFF43B05C.toInt()
        BudgetPace.Pace.TIGHT -> 0xFFF5A623.toInt()
        BudgetPace.Pace.OVER -> 0xFFE5534B.toInt()
    }

    private fun wordmark(accent: Int) = TextStyle(color = ColorProvider(Color(accent)), fontSize = 12.sp, fontWeight = FontWeight.Bold)

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
/** When the widgets were last redrawn, in this process. */
@Volatile private var lastRefreshAt = 0L

/**
 * Redraws the widgets and the watch unless that was done in the last [maxAgeMs]. For opening
 * the app: anything that changes the figures - a sync, a card alert, an import - redraws them
 * itself, so on opening there's only the clock to catch up with, and every open needn't redo it.
 */
suspend fun refreshWidgetsIfStale(context: Context, maxAgeMs: Long = 15 * 60 * 1000L) {
    if (System.currentTimeMillis() - lastRefreshAt < maxAgeMs) return
    refreshWidgets(context)
    refreshCardWidgets(context)
}

suspend fun refreshWidgets(context: Context) {
    lastRefreshAt = System.currentTimeMillis()
    // Worked out once, fresh, for everything this refresh draws.
    runCatching { SharedSnapshot.fresh(context) }
    runCatching { AvailableToSpendWidget().refreshEach(context) }
    // Drawn from the same snapshot, so it moves when this one does.
    refreshTimelineWidgets(context)
    refreshPeriodWidgets(context)
    refreshComingUpWidgets(context)
    refreshAheadWidgets(context)
    // And the watch's complication, for the same reason.
    WatchSync.push(context)
}
