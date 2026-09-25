package com.spendroid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.spendroid.ui.theme.rememberReveal
import com.spendroid.ui.theme.revealWhenSeen
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.CreditCardEngine
import com.spendroid.domain.CategoryTotal
import com.spendroid.domain.TrendSummary
import com.spendroid.domain.TrendsEngine
import com.spendroid.ui.theme.Motion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

internal val InColor = Color(0xFF2E7D32)
internal val OutColor = Color(0xFFC62828)

/**
 * How much of the variable budget is gone, as a fraction. Null when there is no budget to
 * measure against.
 */
private fun budgetUsedFraction(budget: BudgetSnapshot): Float? = BudgetPace.usedFraction(budget)

/** How far through the pay cycle today is, as a fraction. */
private fun cycleElapsedFraction(budget: BudgetSnapshot): Float? =
    BudgetPace.elapsedFraction(budget)

/**
 * "£742 left" reads very differently with 8 days to go than with 24, so say which it is:
 * spending against time, not just the remaining balance.
 */
private fun paceCaption(budget: BudgetSnapshot): String? {
    budgetUsedFraction(budget) ?: return null
    val elapsed = cycleElapsedFraction(budget) ?: return null
    // Measured against what the ring and the headline use, or under carrying over the
    // caption would call the cycle on track beside a ring that says otherwise.
    val against = budget.spendableThisCycle.takeIf { it > 0L } ?: budget.variableMonthlyBudget
    val expected = (against * elapsed).toLong()
    val difference = expected - budget.spentThisCycle
    val throughCycle = "${(elapsed * 100).toInt()}% through the cycle"
    return when {
        difference > 500L -> "$throughCycle · ahead by ${formatMoney(difference, budget.baseCurrency)}"
        difference < -500L -> "$throughCycle · over by ${formatMoney(-difference, budget.baseCurrency)}"
        else -> "$throughCycle · on track"
    }
}

/**
 * The hero's colour, from whether spending is keeping pace with the cycle.
 *
 * Deliberately not from the remaining balance: £200 left is comfortable on day 28 and
 * alarming on day 5, so colouring by the remainder alone would turn the screen red every
 * month as payday approached, and a warning that fires every month is one people learn to
 * ignore. Pace reacts to behaviour instead of to the calendar.
 *
 * Tolerances are fractions of the budget rather than fixed amounts, so the same thresholds
 * suit any income.
 */
private fun heroGradient(budget: BudgetSnapshot): List<Color> =
    when (BudgetPace.of(budget)) {
        BudgetPace.Pace.OVER -> listOf(HeroRedStart, HeroRedEnd)
        BudgetPace.Pace.TIGHT -> listOf(HeroAmberStart, HeroAmberEnd)
        BudgetPace.Pace.ON_TRACK -> listOf(HeroGreenStart, HeroGreenEnd)
    }

/**
 * The pace colours, faded from one to the next. Tipping from on track to tight is exactly the
 * moment worth noticing, and a jump cut is easy to miss while a change of colour is not.
 */
@Composable
private fun animatedHeroGradient(budget: BudgetSnapshot): List<Color> {
    val (start, end) = heroGradient(budget)
    val a by animateColorAsState(start, Motion.change(Motion.LONG), label = "hero start")
    val b by animateColorAsState(end, Motion.change(Motion.LONG), label = "hero end")
    return listOf(a, b)
}

private val HeroGreenStart = Color(0xFF2E7D32)
private val HeroGreenEnd = Color(0xFF1B5E20)
private val HeroAmberStart = Color(0xFFB26A00)
private val HeroAmberEnd = Color(0xFF7A4600)
private val HeroRedStart = Color(0xFF9B2226)
private val HeroRedEnd = Color(0xFF5D1214)

/**
 * Budget used, drawn as an arc. The track marks how far through the cycle today is, so a gap
 * between the two is the whole signal.
 */
@Composable
private fun SpendingPaceRing(budget: BudgetSnapshot) {
    val usedTarget = budgetUsedFraction(budget)
    val elapsedTarget = cycleElapsedFraction(budget)
    if (usedTarget == null) return

    // Both arcs sweep from empty when the ring first appears, then glide to each new value:
    // the grey arc (time gone) leads and the white one (money gone) follows, so the gap
    // between them - which is the whole reading - opens up in front of you.
    var appeared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val elapsedAnim by animateFloatAsState(
        if (appeared) elapsedTarget ?: 0f else 0f,
        Motion.settle(),
        label = "cycle gone",
    )
    val usedAnim by animateFloatAsState(
        if (appeared) usedTarget else 0f,
        Motion.settle(),
        label = "budget used",
    )
    val used = usedAnim
    val elapsed = elapsedTarget?.let { elapsedAnim }

    val spoken = paceCaption(budget)
        ?: "${(usedTarget * 100).toInt()} percent of the budget used"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(78.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Canvas(modifier = Modifier.size(78.dp)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val offset = Offset(inset, inset)

            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            elapsed?.let {
                drawArc(
                    color = Color.White.copy(alpha = 0.45f),
                    startAngle = -90f,
                    sweepAngle = 360f * it,
                    useCenter = false,
                    topLeft = offset,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * used,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(used * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "used",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * The card bill, split into the part already on a closed statement and the part still
 * accruing. Both are owed; only the first has a settled amount, which is why the split is
 * worth showing rather than one total.
 */
@Composable
private fun CardBillCard(bill: CreditCardEngine.CardBill) {
    // Opens to show the card's past statements; the bar fills and a warning pulses when the
    // panel is first seen, not while it is still below the fold.
    var open by rememberSaveable(bill.cardAccountId) { mutableStateOf(false) }
    val reveal = rememberReveal()
    val hasHistory = bill.pastBills.isNotEmpty()
    Card(
        onClick = { if (hasHistory) open = !open },
        modifier = Modifier
            .fillMaxWidth()
            .revealWhenSeen(reveal)
            .semantics(mergeDescendants = true) {
                contentDescription = "${bill.cardLabel}, " +
                    formatMoney(bill.outstandingMinor, bill.currency) + " owed, next payment about " +
                    formatMoney(bill.dueMinor, bill.currency)
                if (hasHistory) stateDescription = if (open) "Past statements shown" else "Past statements hidden"
            },
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize(Motion.change())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    bill.cardLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    formatMoney(bill.outstandingMinor, bill.currency),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Billed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(bill.billedMinor, bill.currency),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Since statement",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(bill.unbilledMinor, bill.currency),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
            if (bill.outstandingMinor > 0L) {
                Spacer(Modifier.height(10.dp))
                val billedShare by animateFloatAsState(
                    if (reveal.shown) bill.billedMinor.toFloat() / bill.outstandingMinor.toFloat() else 0f,
                    Motion.arrive(Motion.LONG),
                    label = "billed share",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(billedShare.coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Box(
                        modifier = Modifier
                            .weight((1f - billedShare).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
            cardPaceLine(bill)?.let { pace ->
                Spacer(Modifier.height(10.dp))
                // Twice, then still: enough to draw the eye once, never enough to nag.
                val pulse = remember { Animatable(1f) }
                LaunchedEffect(reveal.shown, pace.over) {
                    if (!reveal.shown || !pace.over) return@LaunchedEffect
                    repeat(2) {
                        pulse.animateTo(0.45f, tween(420, easing = Motion.Emphasized))
                        pulse.animateTo(1f, tween(420, easing = Motion.Emphasized))
                    }
                }
                Text(
                    pace.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pace.over) OutColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (pace.over) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.graphicsLayer { alpha = pulse.value },
                )
            }
            if (open && hasHistory) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Past statements, and where this one is heading",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                StatementHistory(bill)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    // The total is the bank's own figure, so it is exact. The split across
                    // the statement boundary is only as good as the cycle behind it, which
                    // is why an assumed cycle says so rather than looking authoritative.
                    bill.statementClose?.let { append("Statement closed ${it.format(dateFormat)}") }
                    bill.dueDate?.let { due ->
                        if (isNotEmpty()) append(" · ")
                        append("due ~${due.format(dateFormat)}")
                    }
                    if (bill.cycleSource == CreditCardEngine.CycleSource.ASSUMED) {
                        if (isNotEmpty()) append(" · ")
                        append("cycle estimated")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasHistory) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (open) "Hide past statements ▴" else "Tap to see past statements ▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The card's bills as a line, oldest to newest, ending in a dashed step to where the
 * statement building now is heading. It draws itself in when the panel opens.
 */
@Composable
private fun StatementHistory(bill: CreditCardEngine.CardBill) {
    val points = bill.pastBills.map { (date, amount) -> date.format(monthFormat) to amount } +
        listOfNotNull(
            (bill.projectedMinor ?: bill.unbilledMinor).takeIf { it > 0L }?.let { projected ->
                (bill.nextStatementClose?.format(monthFormat) ?: "Next") to projected
            },
        )
    val projectedLast = points.size > bill.pastBills.size
    if (points.size < 2) return

    val lineColor = MaterialTheme.colorScheme.primary
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axisText)
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drawn.animateTo(1f, Motion.arrive(700)) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics {
                contentDescription = points.joinToString("; ") { (label, amount) ->
                    "$label ${formatMoney(amount, bill.currency)}"
                } + if (projectedLast) " (projected)" else ""
            },
    ) {
        val left = 16.dp.toPx()
        val right = size.width - 16.dp.toPx()
        val top = 16.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val peak = points.maxOf { it.second }.coerceAtLeast(1L).toFloat()
        fun x(i: Int) = left + i * (right - left) / (points.size - 1)
        fun y(v: Long) = bottom - v / peak * (bottom - top)

        val settled = if (projectedLast) points.size - 1 else points.size
        val path = Path().apply {
            (0 until settled).forEach { i -> if (i == 0) moveTo(x(i), y(points[i].second)) else lineTo(x(i), y(points[i].second)) }
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val shown = Path()
        // The settled line takes most of the draw; the projection follows it.
        val lineShare = (drawn.value / 0.8f).coerceIn(0f, 1f)
        measure.getSegment(0f, measure.length * lineShare, shown, true)
        drawPath(shown, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        if (projectedLast && drawn.value > 0.8f) {
            val step = (drawn.value - 0.8f) / 0.2f
            val from = Offset(x(settled - 1), y(points[settled - 1].second))
            val to = Offset(x(settled), y(points[settled].second))
            drawLine(
                lineColor,
                from,
                from + (to - from) * step,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
        }
        points.forEachIndexed { i, (label, amount) ->
            val appear = ((drawn.value * points.size) - i).coerceIn(0f, 1f)
            if (appear <= 0f) return@forEachIndexed
            val c = Offset(x(i), y(amount))
            val projected = projectedLast && i == points.lastIndex
            drawCircle(surface, radius = 5.dp.toPx() * appear, center = c)
            if (projected) {
                drawCircle(lineColor, radius = 3.5.dp.toPx() * appear, center = c, style = Stroke(1.5.dp.toPx()))
            } else {
                drawCircle(lineColor, radius = 3.5.dp.toPx() * appear, center = c)
            }
            val labelText = textMeasurer.measure(label, labelStyle)
            drawText(labelText, topLeft = Offset(c.x - labelText.size.width / 2f, bottom + 3.dp.toPx()))
            // Amounts on the first and the last two points only: enough to read the trend
            // without a number on every dot.
            if (i == 0 || i >= points.size - 2) {
                val amountText = textMeasurer.measure(poundsLabel(amount, bill.currency), labelStyle)
                drawText(amountText, topLeft = Offset(c.x - amountText.size.width / 2f, c.y - amountText.size.height - 4.dp.toPx()))
            }
        }
    }
}

private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

private fun poundsLabel(minor: Long, currency: String): String =
    formatMoney((minor / 100L) * 100L, currency).replace(Regex("[.,]00\\b"), "")

/**
 * What is held against what is owed.
 *
 * Available-to-spend is a budget: a plan derived from recurring income. This is the other
 * question - what actually exists right now - and the two are worth seeing side by side
 * rather than blended into one number that answers neither cleanly.
 */
@Composable
private fun NetPositionCard(accounts: List<AccountEntity>) {
    val currency = accounts.firstOrNull()?.currency ?: "GBP"
    val held = accounts
        .filter { it.accountType != AccountType.CREDIT_CARD }
        .sumOf { it.balanceMinor ?: 0L }
    // A card balance is negative when money is owed on it; a card in credit owes nothing.
    val owed = accounts
        .filter { it.accountType == AccountType.CREDIT_CARD }
        .sumOf { maxOf(0L, -(it.balanceMinor ?: 0L)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Where you stand",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Held",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(held, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Owed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (owed == 0L) formatMoney(0L, currency) else "−" + formatMoney(owed, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = if (owed > 0L) OutColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Net",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(held - owed, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = if (held - owed < 0L) OutColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Shown only on the day itself.
 *
 * Exactly when a delayed payment reappears is not worth predicting - it depends on the
 * biller, and it will be somewhere in the couple of days around the closure. Saying so is
 * more useful than a confident date that turns out wrong.
 */
/**
 * Says so when spending has been left out rather than converted.
 *
 * The totals are short by a known amount, and saying nothing would make them look complete.
 */
@Composable
private fun ForeignCurrencyBanner(currencies: Set<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Spending in ${currencies.sorted().joinToString(", ")} is not counted",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "There is no exchange rate to convert it with, so it is left out rather " +
                        "than added as though it were pounds. The figures below are short by " +
                        "that much.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BankHolidayBanner(holidayName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Today is $holidayName",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Banks are closed, so anything due today will most likely leave your " +
                        "account on the next working day. Today's spending figures may look " +
                        "lower than they really are until it catches up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

private data class MonthTotals(
    val inSum: Long,
    val outSum: Long,
    val currency: String,
    val avgOut: Long,
    val monthCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onRelink: (Connection) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onSetBudgetGoal: (Category, Long) -> Unit,
    onLinkBank: () -> Unit,
) {
    // Pull down to sync, from where the figures are rather than from a button elsewhere.
    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        ) {
            state.bankHolidayToday?.let { name ->
                BankHolidayBanner(name)
                Spacer(Modifier.height(16.dp))
            }
            state.budget?.unconvertedCurrencies?.takeIf { it.isNotEmpty() }?.let { currencies ->
                ForeignCurrencyBanner(currencies)
                Spacer(Modifier.height(16.dp))
            }
            if (state.reauthNeeded.isNotEmpty()) {
                ReauthBanner(state.reauthNeeded, onRelink)
                Spacer(Modifier.height(16.dp))
            }
            state.budget?.let { budget ->
                HeroBudgetCard(budget, syncing = state.syncing)
                Spacer(Modifier.height(16.dp))
            }

            if (state.accounts.isNotEmpty()) {
                NetPositionCard(state.accounts)
                Spacer(Modifier.height(16.dp))
            }

            state.budget?.cardBills.orEmpty()
                .filter { it.outstandingMinor > 0L }
                .forEach { bill ->
                    CardBillCard(bill)
                    Spacer(Modifier.height(16.dp))
                }

            // Category breakdown and trends live on Spending → Insights. The dashboard reports
            // the state of the cycle; analysing it is a different job and a different screen.

            // The list of accounts belongs to the Accounts tab; this is only the way in on the
            // first run, when there is nothing else on the screen to act on.
            if (state.accounts.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No accounts linked yet.", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Link a bank and SpenDroid will work out your pay cycle from what it finds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onLinkBank) { Text("Link a bank") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.height(16.dp))

            // The list lives on its own destination now; the dashboard keeps a way in.
            TextButton(
                onClick = onSeeAllTransactions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("See all transactions")
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            state.linkProgress?.let { progress ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
            }
            // What the last pull-to-refresh did about the bank's allowance.
            state.syncNote?.let { note ->
                Spacer(Modifier.height(12.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun ReauthBanner(
    connections: List<Connection>,
    onRelink: (Connection) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Bank access expired", style = MaterialTheme.typography.titleMedium)
            Text(
                "Reconnect to keep syncing these accounts:",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            connections.forEach { connection ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        connection.institutionName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onRelink(connection) }) {
                        Text("Reconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBudgetCard(budget: BudgetSnapshot, syncing: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(animatedHeroGradient(budget)))
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Budget until next income",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    )
                    SyncedTick(syncing)
                }
                budget.nextIncomeDate?.let { next ->
                    val today = budget.asOf
                    val days = max(0L, ChronoUnit.DAYS.between(today, next))
                    Text(
                        when {
                            // Payday has come but the salary has not cleared: the cycle turns
                            // when it does, so say what is being waited for.
                            next == today -> "Income due today · the new cycle starts when it clears"
                            next.isBefore(today) ->
                                "Income expected ${next.format(dateFormat)} · not cleared yet"
                            else -> "Next income ${next.format(dateFormat)} · in $days day${if (days == 1L) "" else "s"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpendingPaceRing(budget)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            when (budget.budgetModel) {
                                BudgetModel.ROLLOVER -> "Available to spend, balance carried over"
                                else -> "Available to spend"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        RollingAmount(
                            modifier = Modifier.shimmer(syncing),
                            minor = budget.availableToSpend,
                            currency = budget.baseCurrency,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        // Fades rather than jumps, so a change of verdict is noticed.
                        AnimatedContent(
                            targetState = paceCaption(budget),
                            transitionSpec = {
                                fadeIn(Motion.arrive()).togetherWith(fadeOut(Motion.change(Motion.SHORT)))
                            },
                            label = "pace caption",
                        ) { caption ->
                            if (caption != null) {
                                Text(
                                    caption,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    HeroStat("Spent today", budget.spentToday, budget.baseCurrency)
                    HeroStat("This cycle", budget.spentThisCycle, budget.baseCurrency)
                    // Deliberately beside the budget rather than folded into it: the two
                    // answer different questions and the gap between them is the point.
                    if (budget.budgetModel == BudgetModel.SHOW_BOTH) {
                        budget.potBalanceMinor?.let { pot ->
                            HeroStat("In the account", pot, budget.baseCurrency)
                        }
                    }
                }
                if (budget.budgetModel == BudgetModel.ROLLOVER) {
                    budget.potBalanceMinor?.let { pot ->
                        Text(
                            "${formatMoney(pot, budget.baseCurrency)} in the account, less what is due before payday",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
                // Zero cannot say how far past zero, and the difference matters.
                if (budget.shortfallMinor > 0L) {
                    Text(
                        "${formatMoney(budget.shortfallMinor, budget.baseCurrency)} short of covering what is still to come out",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
                // Counted at the bill, card spending is out of this figure by design - so say
                // where it went, rather than let the next cycle be a surprise.
                if (budget.cardsNextCycleMinor > 0L) {
                    Text(
                        "About ${formatMoney(budget.cardsNextCycleMinor, budget.baseCurrency)} of " +
                            "card bills fall in your next cycle",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
                if (budget.designationLost) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The income you picked to set the cycle is no longer being detected — " +
                            "possibly renamed by your bank. Using the largest income instead; " +
                            "pick it again under Recurring rules.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Income ${formatMoney(budget.averageMonthlyIncome, budget.baseCurrency)}/mo · " +
                        "Fixed ${formatMoney(budget.fixedMonthlyOutgoings, budget.baseCurrency)}/mo · " +
                        "Variable ${formatMoney(budget.variableMonthlyBudget, budget.baseCurrency)}/mo",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                if (budget.upcomingFixed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Known deductions to come",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    budget.upcomingFixed.take(5).forEach { payment ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(payment.rule.payee.tidyPayee(), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                // A card bill is forecast from the outstanding balance, so it
                                // should not read as a confirmed amount and date.
                                val isForecast = payment.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                                Text(
                                    if (isForecast) {
                                        "estimated · due ~${payment.dueDate.format(dateFormat)}"
                                    } else {
                                        "due ${payment.dueDate.format(dateFormat)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                            }
                            Text(
                                recurringAmount(payment.amountMinor, payment.rule.perOccurrence, payment.rule.currency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
                if (budget.incomeRules.isNotEmpty() && budget.fixedRules.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Detected ${budget.incomeRules.size} recurring ${if (budget.incomeRules.size == 1) "income" else "incomes"} " +
                            "and ${budget.fixedRules.size} recurring ${if (budget.fixedRules.size == 1) "payment" else "payments"}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, minor: Long, currency: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
        RollingAmount(
            minor = minor,
            currency = currency,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

private fun syncTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))

/**
 * A soft light passing across a figure while a sync is running: the number on show is the
 * last one known, and this says a newer one is on its way without hiding it.
 */
@Composable
private fun Modifier.shimmer(active: Boolean): Modifier {
    if (!active) return this
    val sweep = rememberInfiniteTransition(label = "sync shimmer")
    val at by sweep.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmer position",
    )
    return drawWithContent {
        drawContent()
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                start = Offset(size.width * (at - 0.4f), 0f),
                end = Offset(size.width * at, size.height),
            ),
        )
    }
}

/**
 * A tick that draws itself when a sync finishes, then fades: a sync that landed looks
 * different from a pull that did nothing, and the figures rolling below say what it changed.
 */
@Composable
private fun SyncedTick(syncing: Boolean) {
    var wasSyncing by remember { mutableStateOf(syncing) }
    val drawn = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(syncing) {
        if (wasSyncing && !syncing) {
            shown.snapTo(1f)
            drawn.snapTo(0f)
            drawn.animateTo(1f, Motion.arrive(400))
            delay(1_600)
            shown.animateTo(0f, Motion.change())
        }
        wasSyncing = syncing
    }
    if (shown.value <= 0f) return
    Canvas(
        modifier = Modifier
            .size(18.dp)
            .graphicsLayer { alpha = shown.value }
            .semantics { contentDescription = "Synced" },
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.76f)
            lineTo(size.width * 0.84f, size.height * 0.28f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * drawn.value, part, true)
        drawPath(part, Color.White, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

