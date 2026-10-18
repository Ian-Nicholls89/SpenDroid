package com.spendroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CycleReview
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Grotesk
import java.time.format.DateTimeFormatter

private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val SHORT = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME = DateTimeFormatter.ofPattern("d MMM")

/** Drawn over the app, outside the Scaffold that gives text its colour everywhere else. */
@Composable
private fun OnCharcoal(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Charcoal.Text, content = content)

@Composable
private fun MomentFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) = OnCharcoal {
    BackHandler(onBack = onBack)
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
        HeaderGlow(accent, height = 300.dp, strength = 0.42f)
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                Wordmark(title, accent)
            }
            content()
        }
    }
}

@Composable
private fun Figure(text: String, colour: Color = Color.White, size: Int = 50) {
    Text(text, fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (-1.5).sp, color = colour)
}

/**
 * Today: one day's allowance, and what's gone from it. The allowance is what's left to spend
 * shared over the days to payday, so spending less today makes every day after a little bigger.
 */
@Composable
fun TodayScreen(state: RootUiState, onBack: () -> Unit) {
    MomentFrame("today", onBack) {
        val budget = state.budget
        if (budget == null) {
            Text("Nothing to show yet.", color = Charcoal.Muted, modifier = Modifier.padding(20.dp))
            return@MomentFrame
        }
        val accent = MaterialTheme.colorScheme.primary
        val money = { m: Long -> formatMoney(m, budget.baseCurrency) }
        val days = (budget.daysUntilNextIncome ?: 1).coerceAtLeast(1)
        // The day's share as it stood this morning: what's left now plus what today has spent.
        val allowance = (budget.availableToSpend + budget.spentToday).coerceAtLeast(0L) / days
        val left = allowance - budget.spentToday
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(budget.asOf.format(LONG_DAY) + " · " + "$days ${if (days == 1) "day" else "days"} to payday", style = MaterialTheme.typography.labelLarge, color = Charcoal.Muted, modifier = Modifier.padding(top = 14.dp))
            Figure(if (left >= 0) money(left) else "−" + money(-left), if (left >= 0) Color.White else Charcoal.Bad)
            Text(if (left >= 0) "left for today, of ${money(allowance)}" else "over today's ${money(allowance)}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFAAB0BB))
            Spacer(Modifier.height(14.dp))
            LabelledBar(
                fraction = if (allowance > 0) budget.spentToday.toFloat() / allowance else 1f,
                label = "${money(budget.spentToday)} spent today",
                colour = if (left >= 0) accent else Charcoal.Bad,
                height = 30.dp,
            )
        }
        val today = state.transactions.filter { tx ->
            tx.amountMinor < 0 && !tx.isInternalTransfer && RecurringAnalyzer.parseBookingDate(tx.bookingDate) == budget.asOf
        }
        Spacer(Modifier.height(14.dp))
        Panel(Modifier.padding(horizontal = 14.dp)) {
            SectionHeading("Today")
            if (today.isEmpty()) Text("Nothing spent yet today.", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp))
            today.forEach { tx ->
                val category = CategoryEngine.classify(tx, state.categoryRules, budget.cardPaymentKeys, budget.creditCardAccountIds)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryTile(category, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tx.payee.tidyPayee().ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(category.label, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                    }
                    Text(money(-tx.amountMinor), fontFamily = Grotesk, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().background(Charcoal.Panel, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Droid(DroidNow.face, 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "What you don't spend today is shared over the days to payday, so every day after gets a little more.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6D8DE),
            )
        }
        val week = budget.lastSevenDaysMinor
        if (week.isNotEmpty() && allowance > 0) {
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.padding(horizontal = 14.dp)) {
                SectionHeading("The last seven days", trailing = "red: over the day's share")
                val most = (week.maxOrNull() ?: 0L).coerceAtLeast(allowance).toFloat()
                Row(Modifier.fillMaxWidth().height(90.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    week.forEachIndexed { i, spent ->
                        val day = budget.asOf.minusDays((week.size - 1 - i).toLong())
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.fillMaxWidth().height((60 * (spent / most)).coerceAtLeast(2f).dp)
                                    .background(if (spent > allowance) Charcoal.Bad else accent, RoundedCornerShape(5.dp)),
                            )
                            Text(day.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Payday: the droid celebrates, the bar starts fresh, and the cycle just finished is summed up
 * in a line, with the way into its review.
 */
@Composable
fun PaydayScreen(state: RootUiState, review: CycleReview?, onReview: () -> Unit, onDone: () -> Unit) = OnCharcoal {
    BackHandler(onBack = onDone)
    val budget = state.budget ?: return@OnCharcoal
    val accent = MaterialTheme.colorScheme.primary
    val money = { m: Long -> formatMoney(m, budget.baseCurrency) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1A13))) {
        HeaderGlow(accent, height = 520.dp, strength = 0.6f)
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(30.dp))
            Droid(DroidFace(DroidMood.PAYDAY), 150.dp)
            Text("PAYDAY", fontFamily = Grotesk, letterSpacing = 4.sp, fontSize = 13.sp, color = Color(0xFFC9F5D9), modifier = Modifier.padding(top = 8.dp))
            budget.primaryIncomeRule?.let { pay ->
                Figure("+" + money(pay.amountMinor))
                Text("${pay.payee.tidyPayee()} · ${budget.cycleStart.format(SHORT)}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFC9F5D9))
            }
            Spacer(Modifier.height(22.dp))
            LabelledBar(fraction = 0f, label = "", colour = accent, height = 26.dp, tick = 0f)
            val days = budget.daysUntilNextIncome
            Text(
                "A fresh bar" + (days?.let { ": $it days, ${money(budget.availableToSpend)} to spend" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD8F7E4), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(26.dp))
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                SectionHeading("Last cycle")
                if (review == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Adding it up…", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted)
                    }
                } else {
                    Text(
                        if (review.leftOverMinor >= 0) "${money(review.leftOverMinor)} left over" else "${money(-review.leftOverMinor)} over",
                        fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("Ahead of the days on ${review.daysAhead} of ${review.days}.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAB0BB))
                    Spacer(Modifier.height(10.dp))
                    BigButton("See the review", null, onReview, Modifier.fillMaxWidth(), colour = accent, textColour = Color(0xFF111111))
                }
            }
            Spacer(Modifier.height(10.dp))
            BigButton("Done", null, onDone, Modifier.fillMaxWidth())
        }
    }
}

/** The last cycle as an editorial: a headline, the left-over figure, each day, and a few facts. */
@Composable
fun ReviewScreen(review: CycleReview?, loading: Boolean, onBack: () -> Unit) {
    MomentFrame("review", onBack) {
        if (review == null) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (loading) "Adding up the last cycle…" else "There's no full cycle to look back on yet.", color = Charcoal.Muted)
            }
            return@MomentFrame
        }
        val accent = MaterialTheme.colorScheme.primary
        val money = { m: Long -> formatMoney(m, review.currency) }
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(
                "${review.start.format(TIME)} – ${review.end.format(TIME)}".uppercase(),
                fontWeight = FontWeight.Black, letterSpacing = 1.6.sp, fontSize = 11.sp, color = accent, modifier = Modifier.padding(top = 14.dp),
            )
            Text(review.headline, fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 31.sp, modifier = Modifier.padding(top = 6.dp))
            Text(review.standfirst, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC9CCD3), modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
                Figure(money(kotlin.math.abs(review.leftOverMinor)), if (review.leftOverMinor >= 0) accent else Charcoal.Bad, 54)
                Spacer(Modifier.width(14.dp))
                Text(
                    if (review.leftOverMinor >= 0) "left over, and carried into the new cycle" else "over, and taken from the new cycle",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Color.Transparent).padding(start = 0.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Panel(Modifier.padding(horizontal = 14.dp)) {
            SectionHeading("Each day", trailing = "red: over the day's share")
            val most = review.daily.maxOfOrNull { it.second }?.coerceAtLeast(1L)?.toFloat() ?: 1f
            Row(Modifier.fillMaxWidth().height(80.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                review.daily.forEach { (_, spent) ->
                    Box(
                        Modifier.weight(1f).height((64 * spent / most).coerceAtLeast(2f).dp)
                            .background(if (review.dailyShareMinor in 1 until spent) Charcoal.Bad else accent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.padding(horizontal = 14.dp)) {
            Fact("Spent", money(review.spentMinor))
            review.biggestDay?.let { (day, amount) -> Fact("Biggest day", "${day.format(SHORT)} · ${money(amount)}") }
            review.mostVisited?.let { (payee, times) -> Fact("Most visited", "${payee.tidyPayee()} · $times times") }
            Fact("Days ahead of pace", "${review.daysAhead} of ${review.days}")
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Grotesk, fontWeight = FontWeight.Bold)
    }
}
