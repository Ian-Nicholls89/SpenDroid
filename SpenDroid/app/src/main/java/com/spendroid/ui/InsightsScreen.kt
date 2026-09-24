package com.spendroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CategoryTotal
import com.spendroid.domain.TrendSummary
import com.spendroid.domain.TrendsEngine
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import com.spendroid.domain.CategoryTrend
import com.spendroid.domain.MonthSummary
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.RecurringAnalyzer
import java.time.LocalDate
import com.spendroid.domain.TrendBasis
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.spendroid.ui.theme.Motion
import com.spendroid.ui.theme.rememberReveal
import com.spendroid.ui.theme.revealWhenSeen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where spending is analysed, as opposed to the dashboard, which says where the cycle
 * stands. Both engines walk the whole history, so both are keyed on the data rather than
 * recomputed on every recomposition.
 */
@Composable
fun InsightsScreen(
    state: RootUiState,
    onSetBudgetGoal: (Category, Long) -> Unit,
) {
    val cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty()
    val cardAccountIds = state.budget?.creditCardAccountIds.orEmpty()
    var period by rememberSaveable { mutableStateOf(SpendingPeriod.CYCLE) }
    val windowed = remember(state.transactions, period, state.budget?.cycleStart) {
        period.filter(state.transactions, state.budget?.cycleStart)
    }
    val breakdown = remember(windowed, state.categoryRules, cardPaymentKeys) {
        CategoryEngine.spendingBreakdown(
            windowed,
            state.categoryRules,
            cardPaymentKeys,
            cardAccountIds,
        )
    }
    val trends = remember(state.transactions) { TrendsEngine.analyze(state.transactions) }
    val categoryTrends = remember(state.transactions, state.categoryRules, cardPaymentKeys) {
        // The real dates the main income actually landed on, which is where each cycle
        // began. Derived rather than re-predicted, so the periods match what happened.
        val cycleStarts = state.budget?.primaryIncomeRule?.let { rule ->
            state.transactions
                .filter { RecurringAnalyzer.matches(rule, it) }
                .mapNotNull { RecurringAnalyzer.parseBookingDate(it.bookingDate) }
                .distinct()
                .sorted()
        }.orEmpty()
        TrendsEngine.categoryTrends(
            transactions = state.transactions,
            userRules = state.categoryRules,
            cardPaymentKeys = cardPaymentKeys,
            creditCardAccountIds = cardAccountIds,
            cycleStarts = cycleStarts,
            currentCycleEnd = state.budget?.cycleEnd,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
    ) {
        if (breakdown.isEmpty() && trends.months.size < 2) {
            Text(
                "Once a few weeks of transactions have synced, spending by category and " +
                    "month-by-month trends will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        PeriodSelector(period) { period = it }
        Spacer(Modifier.height(12.dp))

        if (breakdown.isNotEmpty()) {
            CategoryBreakdownCard(
                breakdown = breakdown,
                variableBudget = state.budget?.variableMonthlyBudget,
                goals = state.budgetGoals,
                onSetGoal = onSetBudgetGoal,
            )
            Spacer(Modifier.height(16.dp))
        }
        if (trends.months.size >= 2) {
            TrendsCard(trends, categoryTrends)
        }
    }
}

/**
 * Which window the breakdown covers.
 *
 * Cycle is the default because every other figure in the app is measured that way. Week and
 * month deliberately cut across pay cycles, so their totals will not match the home screen -
 * which is why the control says which window is in view rather than leaving someone to find
 * the discrepancy and distrust both numbers.
 */
private enum class SpendingPeriod(val label: String, val note: String) {
    WEEK("Week", "The last 7 days"),
    MONTH("Month", "This calendar month"),
    CYCLE("This cycle", "Since your last income, as everywhere else"),
    ALL("All time", "Everything held locally"),
    ;

    fun filter(
        transactions: List<TransactionEntity>,
        cycleStart: LocalDate?,
        today: LocalDate = LocalDate.now(),
    ): List<TransactionEntity> {
        val from = when (this) {
            WEEK -> today.minusDays(6)
            MONTH -> today.withDayOfMonth(1)
            CYCLE -> cycleStart ?: return transactions
            ALL -> return transactions
        }
        return transactions.filter { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
            !date.isBefore(from)
        }
    }
}

@Composable
private fun PeriodSelector(selected: SpendingPeriod, onSelect: (SpendingPeriod) -> Unit) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SpendingPeriod.entries.toList(), key = { it.name }) { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option.label, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            selected.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BudgetGoalDialog(
    category: Category,
    current: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(current?.let { (it / 100).toString() } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly cap for ${category.label}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    label = { Text("Amount") },
                    prefix = { Text("£") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave empty to remove the cap.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm((text.toLongOrNull() ?: 0L) * 100) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CategoryBreakdownCard(
    breakdown: List<CategoryTotal>,
    variableBudget: Long?,
    goals: List<BudgetGoalEntity>,
    onSetGoal: (Category, Long) -> Unit,
) {
    var editing by remember { mutableStateOf<Category?>(null) }
    val goalFor = remember(goals) { goals.associateBy { it.category } }
    val reveal = rememberReveal()
    Card(modifier = Modifier.fillMaxWidth().revealWhenSeen(reveal)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Spending by category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // Label above the bar rather than beside it: the old fixed 120.dp column clipped
            // "Bills & Utilities" and "Entertainment" on narrow screens.
            breakdown.forEach { total -> key(total.category) {
                val maxAmount = breakdown.firstOrNull()?.amountMinor ?: 1L
                val fraction = if (maxAmount > 0) total.amountMinor.toFloat() / maxAmount.toFloat() else 0f
                val visual = total.category.visual
                val goal = goalFor[total.category.name]?.limitMinor
                val exceededBy = goal?.let { (total.amountMinor - it).takeIf { over -> over > 0L } }
                val overBudget = exceededBy != null
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = total.category }
                        .padding(vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            visual.icon,
                            contentDescription = null,
                            tint = visual.color,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            total.category.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${total.count}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (goal != null) {
                                formatMoney(total.amountMinor, total.currency) + " / " +
                                    formatMoney(goal, total.currency)
                            } else {
                                formatMoney(total.amountMinor, total.currency)
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = if (overBudget) OutColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Against the cap when one is set, otherwise against the largest category -
                    // the bar answers a different question in each case. It fills when first
                    // seen, and slides to its new length when the period changes.
                    val target = if (goal != null && goal > 0L) {
                        (total.amountMinor.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
                    } else {
                        fraction
                    }
                    val filled by animateFloatAsState(
                        if (reveal.shown) target else 0f,
                        tween(Motion.LONG + breakdown.indexOf(total) * 40, easing = Motion.EmphasizedDecelerate),
                        label = "category bar",
                    )
                    LinearProgressIndicator(
                        progress = { filled },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (overBudget) OutColor else visual.color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    exceededBy?.let { over ->
                        Text(
                            "${formatMoney(over, total.currency)} over",
                            style = MaterialTheme.typography.labelSmall,
                            color = OutColor,
                        )
                    }
                }
            } }
            editing?.let { category ->
                BudgetGoalDialog(
                    category = category,
                    current = goalFor[category.name]?.limitMinor,
                    onDismiss = { editing = null },
                    onConfirm = { limit ->
                        onSetGoal(category, limit)
                        editing = null
                    },
                )
            }
            if (variableBudget != null && variableBudget > 0) {
                Spacer(Modifier.height(8.dp))
                val totalSpent = breakdown.sumOf { it.amountMinor }
                val remaining = (variableBudget - totalSpent).coerceAtLeast(0)
                val currency = breakdown.firstOrNull()?.currency ?: "GBP"
                Text(
                    "Total variable: ${formatMoney(totalSpent, currency)} of ${formatMoney(variableBudget, currency)} budget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrendsCard(trends: TrendSummary, categoryTrends: List<CategoryTrend>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Income and spending",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Last ${trends.months.size} months",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendKey(SeriesIncome, "Income")
                LegendKey(SeriesSpending, "Spending")
            }
            Spacer(Modifier.height(10.dp))
            IncomeSpendingChart(trends.months)

            if (categoryTrends.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text(
                    "What is moving",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (categoryTrends.first().basis) {
                        TrendBasis.PAY_CYCLE ->
                            "The first ${categoryTrends.first().windowDays} days of each pay cycle, so far this one"
                        TrendBasis.ROLLING_DAYS ->
                            "Each period is ${categoryTrends.first().windowDays} days"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                categoryTrends.take(5).forEachIndexed { index, trend ->
                    CategorySparkline(trend, index)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Income and spending on one scale.
 *
 * Two measures in the same unit belong on the same axis: a second y-scale can be positioned
 * to make any two lines tell whatever story is wanted, and the gap between these two is the
 * whole point of drawing them together.
 */
@Composable
private fun IncomeSpendingChart(months: List<MonthSummary>) {
    if (months.size < 2) {
        Text(
            "Two months of history are needed before a trend means anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val currency = months.last().currency
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axisText)
    val haptics = LocalHapticFeedback.current

    val peak = months.maxOf { maxOf(it.income, it.spending) }.coerceAtLeast(1L)
    // Round the top of the scale up to something a label can name honestly.
    val step = niceStep(peak)
    val top = ((peak + step - 1) / step) * step

    // Each line traces itself left to right the first time the chart is seen - spending
    // first, income a beat after - and the end dots arrive with their lines.
    val reveal = rememberReveal()
    val spendDraw = remember { Animatable(0f) }
    val incomeDraw = remember { Animatable(0f) }
    LaunchedEffect(reveal.shown) {
        if (!reveal.shown) return@LaunchedEffect
        launch { spendDraw.animateTo(1f, tween(900, easing = Motion.EmphasizedDecelerate)) }
        delay(120)
        incomeDraw.animateTo(1f, tween(900, easing = Motion.EmphasizedDecelerate))
    }

    // When the figures change - a sync lands - the lines bend into their new shape rather
    // than being redrawn, so it is visible what moved. Points are matched by month.
    var from by remember { mutableStateOf(months) }
    var previousTop by remember { mutableStateOf(top) }
    val reshape = remember { Animatable(1f) }
    LaunchedEffect(months) {
        if (from == months) return@LaunchedEffect
        reshape.snapTo(0f)
        reshape.animateTo(1f, tween(Motion.LONG, easing = Motion.Emphasized))
        // Settled: this shape and scale are where the next change starts from.
        from = months
        previousTop = top
    }
    val previous = remember(from) { from.associateBy { it.month } }

    // Touch or drag along the chart to read any month exactly.
    var selected by remember(months) { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.revealWhenSeen(reveal)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics {
                    contentDescription = "Income and spending over ${months.size} months. " +
                        months.joinToString("; ") {
                            "${it.month.monthLabel()}: income ${formatMoney(it.income, currency)}, " +
                                "spending ${formatMoney(it.spending, currency)}"
                        }
                }
                .pointerInput(months) {
                    fun indexAt(x: Float): Int {
                        val left = 8.dp.toPx()
                        val right = size.width - 8.dp.toPx()
                        val t = ((x - left) / (right - left)).coerceIn(0f, 1f)
                        return Math.round(t * (months.size - 1))
                    }
                    fun choose(x: Float) {
                        val i = indexAt(x)
                        if (i != selected) {
                            selected = i
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        choose(down.position.x)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                choose(change.position.x)
                                // Only a sideways drag is the chart's; up and down still scrolls.
                                if (kotlin.math.abs(change.position.x - change.previousPosition.x) >
                                    kotlin.math.abs(change.position.y - change.previousPosition.y)
                                ) {
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val chartTop = 10.dp.toPx()
            val t = reshape.value
            val scaleTop = previousTop + (top - previousTop) * t

            fun x(i: Int) = left + i * (right - left) / (months.size - 1)
            fun y(v: Float) = bottom - (v / scaleTop.toFloat()) * (bottom - chartTop)
            fun value(i: Int, pick: (MonthSummary) -> Long): Float {
                val now = pick(months[i]).toFloat()
                val was = previous[months[i].month]?.let(pick)?.toFloat() ?: now
                return was + (now - was) * t
            }

            var line = 0L
            while (line <= top) {
                drawLine(
                    color = gridColor,
                    start = Offset(left, y(line.toFloat())),
                    end = Offset(right, y(line.toFloat())),
                    strokeWidth = 1f,
                )
                line += step
            }

            listOf(
                Triple(SeriesSpending, spendDraw.value) { m: MonthSummary -> m.spending },
                Triple(SeriesIncome, incomeDraw.value) { m: MonthSummary -> m.income },
            ).forEach { (color, drawn, pick) ->
                if (drawn <= 0f) return@forEach
                val full = Path().apply {
                    months.indices.forEach { i ->
                        if (i == 0) moveTo(x(i), y(value(i, pick))) else lineTo(x(i), y(value(i, pick)))
                    }
                }
                val measure = PathMeasure().apply { setPath(full, false) }
                val shown = Path()
                measure.getSegment(0f, measure.length * drawn, shown, true)
                drawPath(shown, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                // The last point is the one being asked about, so it alone is marked - and it
                // arrives as its line does.
                val dot = ((drawn - 0.85f) / 0.15f).coerceIn(0f, 1f)
                if (dot > 0f) {
                    val last = Offset(x(months.lastIndex), y(value(months.lastIndex, pick)))
                    drawCircle(surface, radius = 5.dp.toPx() * dot, center = last)
                    drawCircle(color, radius = 3.5.dp.toPx() * dot, center = last)
                }
            }

            selected?.let { i ->
                val gx = x(i)
                drawLine(guideColor, Offset(gx, chartTop), Offset(gx, bottom), strokeWidth = 1.dp.toPx())
                listOf(SeriesSpending to value(i) { it.spending }, SeriesIncome to value(i) { it.income })
                    .forEach { (color, v) ->
                        drawCircle(surface, radius = 6.dp.toPx(), center = Offset(gx, y(v)))
                        drawCircle(color, radius = 4.dp.toPx(), center = Offset(gx, y(v)))
                    }
            }

            val first = textMeasurer.measure(months.first().month.monthLabel(), labelStyle)
            val last = textMeasurer.measure(months.last().month.monthLabel(), labelStyle)
            drawText(first, topLeft = Offset(left, bottom + 4.dp.toPx()))
            drawText(last, topLeft = Offset(right - last.size.width, bottom + 4.dp.toPx()))
        }

        Spacer(Modifier.height(8.dp))
        // The reading beneath follows the touch, and falls back to the latest month.
        val shown = months[selected ?: months.lastIndex]
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                (if (selected != null) "${shown.month.monthLabel()} · " else "") +
                    "Income ${formatMoney(shown.income, currency)}",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = SeriesIncome,
            )
            Text(
                "Spending ${formatMoney(shown.spending, currency)}",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = SeriesSpending,
            )
        }
    }
}

/**
 * One category's run of months, scaled to its own range.
 *
 * The shape is what is being read, not the height, so each is scaled to itself - the figures
 * beside it carry the magnitude. Direction is written out as well as coloured, because a
 * colour alone is not something everyone can read.
 */
@Composable
private fun CategorySparkline(trend: CategoryTrend, index: Int = 0) {
    val visual = trend.category.visual
    val change = trend.change
    val rising = change != null && change > 0.02f
    val falling = change != null && change < -0.02f
    val lineColor = when {
        rising -> SeriesSpending
        falling -> SeriesFalling
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Each line traces in when it comes into view, a beat after the one above, and the
    // percentage counts up alongside it.
    val reveal = rememberReveal()
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(reveal.shown) {
        if (!reveal.shown) return@LaunchedEffect
        delay(index * 60L)
        drawn.animateTo(1f, tween(700, easing = Motion.EmphasizedDecelerate))
    }
    val percent = ((change ?: 0f).let { kotlin.math.abs(it) } * 100).toInt()
    val shownPercent by animateIntAsState(
        if (reveal.shown) percent else 0,
        tween(700, delayMillis = index * 60, easing = Motion.EmphasizedDecelerate),
        label = "trend percent",
    )

    Column(modifier = Modifier.fillMaxWidth().revealWhenSeen(reveal)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(visual.color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    visual.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                trend.category.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                when {
                    change == null -> "new"
                    rising -> "+$shownPercent%"
                    falling -> "−$shownPercent%"
                    else -> "steady"
                },
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = lineColor,
            )
        }
        Spacer(Modifier.height(4.dp))
        val values = trend.seriesMinor
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            if (values.size < 2 || drawn.value <= 0f) return@Canvas
            val lo = values.min()
            val hi = values.max()
            val span = (hi - lo).coerceAtLeast(1L).toFloat()
            val path = Path()
            values.forEachIndexed { i, v ->
                val px = i * size.width / (values.size - 1)
                val py = size.height - 2.dp.toPx() -
                    ((v - lo) / span) * (size.height - 4.dp.toPx())
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val shown = Path()
            measure.getSegment(0f, measure.length * drawn.value, shown, true)
            drawPath(shown, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val dot = ((drawn.value - 0.85f) / 0.15f).coerceIn(0f, 1f)
            val lastY = size.height - 2.dp.toPx() -
                ((values.last() - lo) / span) * (size.height - 4.dp.toPx())
            if (dot > 0f) drawCircle(lineColor, radius = 3.dp.toPx() * dot, center = Offset(size.width, lastY))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${formatMoney(trend.firstMinor, trend.currency)} → ${formatMoney(trend.lastMinor, trend.currency)} ${if (trend.basis == TrendBasis.PAY_CYCLE) "a cycle" else "per ${trend.windowDays} days"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A grid step that divides the scale into three or four namable lines. */
private fun niceStep(peak: Long): Long {
    val rough = peak / 3L
    val magnitude = generateSequence(1L) { it * 10L }.first { it * 10L > rough.coerceAtLeast(1L) }
    return listOf(1L, 2L, 5L, 10L)
        .map { it * magnitude }
        .first { it >= rough }
}

private fun YearMonth.monthLabel(): String = format(monthLabelFormat)

private val monthLabelFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yy")

// Blue against orange, not green against red: that pair separates by a Delta E of about 4
// for the commonest colour blindness, against a floor of 8, so one reader in twelve would
// see a single line. Both series are labelled in words as well.
private val SeriesIncome = Color(0xFF2A78D6)
private val SeriesSpending = Color(0xFFEB6834)
private val SeriesFalling = Color(0xFF1BAF7A)
