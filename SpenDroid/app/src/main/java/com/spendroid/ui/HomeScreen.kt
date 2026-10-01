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

internal val InColor = Color(0xFF5FD38A)
internal val OutColor = Color(0xFFE5534B)

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
internal fun paceCaption(budget: BudgetSnapshot): String? {
    budgetUsedFraction(budget) ?: return null
    val elapsed = cycleElapsedFraction(budget) ?: return null
    // Measured against what the ring and the headline use, or under carrying over the
    // caption would call the cycle on track beside a ring that says otherwise.
    val against = budget.spendableThisCycle.takeIf { it > 0L } ?: budget.variableMonthlyBudget
    val expected = (against * elapsed).toLong()
    val used = if (budget.spendableThisCycle > 0L) budget.usedThisCycle else budget.spentThisCycle
    val difference = expected - used
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
internal fun CardBillCard(bill: CreditCardEngine.CardBill) {
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
                    if (bill.pendingMinor > 0L) {
                        Text(
                            "incl. ${formatMoney(bill.pendingMinor, bill.currency)} pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
internal fun NetPositionCard(
    accounts: List<AccountEntity>,
    cardBills: List<CreditCardEngine.CardBill> = emptyList(),
) {
    val currency = accounts.firstOrNull()?.currency ?: "GBP"
    val held = accounts
        .filter { it.accountType != AccountType.CREDIT_CARD }
        .sumOf { it.balanceMinor ?: 0L }
    // What each card owes as the card panels work it out - the statement added up plus what
    // has been spent since - so the two agree. The bank's reported balance is used only for
    // a card with no worked-out bill; it was stale enough to be £438 short.
    val billsByCard = cardBills.associateBy { it.cardAccountId }
    val owed = accounts
        .filter { it.accountType == AccountType.CREDIT_CARD }
        .sumOf { card ->
            billsByCard[card.id]?.outstandingMinor ?: maxOf(0L, -(card.balanceMinor ?: 0L))
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeading("Where you stand")
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
internal fun ForeignCurrencyBanner(currencies: Set<String>) {
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
internal fun BankHolidayBanner(holidayName: String) {
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
internal val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
internal fun ReauthBanner(
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
internal fun HeroStat(label: String, minor: Long, currency: String) {
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
internal fun Modifier.shimmer(active: Boolean): Modifier {
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
internal fun SyncedTick(syncing: Boolean) {
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


/** "Today incl. £39.90 on cards · £39.90 pending", or null when today is all booked account spending. */
internal fun todayNote(budget: BudgetSnapshot): String? {
    val parts = buildList {
        if (budget.spentTodayOnCardsMinor > 0L) add("${formatMoney(budget.spentTodayOnCardsMinor, budget.baseCurrency)} on cards")
        if (budget.spentTodayPendingMinor > 0L) add("${formatMoney(budget.spentTodayPendingMinor, budget.baseCurrency)} pending")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Today incl. ")
}
