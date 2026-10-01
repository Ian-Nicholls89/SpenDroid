package com.spendroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.NotificationSpend
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import java.time.format.DateTimeFormatter

private val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")

/**
 * The evening roundup as a page of its own, opened from its notification: the day's figure,
 * the week so far, what leaves tomorrow, and anything waiting on you. The watch shows the same.
 */
@Composable
fun RoundupScreen(state: RootUiState, onBack: () -> Unit, onSeeQuestions: () -> Unit) {
    BackHandler(onBack = onBack)
    val budget = state.budget
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
        HeaderGlow(accent, height = 300.dp, strength = 0.42f)
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                Wordmark("Roundup", accent)
            }
            if (budget == null) {
                Text("Nothing to report yet - link a bank and sync.", color = Charcoal.Muted, modifier = Modifier.padding(20.dp))
                return@Column
            }
            val money = { minor: Long -> formatMoney(minor, budget.baseCurrency) }
            val pace = BudgetPace.of(budget)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(budget.asOf.format(LONG_DAY), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.78f))
                Text("${money(budget.availableToSpend)} left", fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 40.sp, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(paceWord(pace), paceColour(pace))
                    budget.daysUntilNextIncome?.takeIf { it > 0 }?.let { d ->
                        Text("${money(budget.availableToSpend / d)} a day for $d day${if (d == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.padding(horizontal = 14.dp)) {
                SectionHeading("This week")
                Spacer(Modifier.height(12.dp))
                WeekBars(budget.lastSevenDaysMinor, budget.asOf, perDay = budget.daysUntilNextIncome?.takeIf { it > 0 }?.let { (budget.availableToSpend + budget.spentToday) / (it + 1) })
                Spacer(Modifier.height(12.dp))
                val today = budget.spentTodayFromAccountsMinor + budget.spentTodayOnCardsMinor
                val earlier = budget.lastSevenDaysMinor.dropLast(1)
                val usual = earlier.takeIf { it.isNotEmpty() }?.average()?.toLong()
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    HeroStat("Spent today", today, budget.baseCurrency)
                    HeroStat("On cards", budget.spentTodayOnCardsMinor, budget.baseCurrency)
                    usual?.let { u ->
                        Column {
                            Text("vs usual", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            val diff = today - u
                            Text(
                                (if (diff > 0) "+" else "−") + money(kotlin.math.abs(diff)),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (diff > 0) Charcoal.Warn else Charcoal.In,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val tomorrow = budget.asOf.plusDays(1)
            val dueTomorrow = budget.upcomingFixed.filter { it.dueDate == tomorrow }
            Panel(Modifier.padding(horizontal = 14.dp)) {
                SectionHeading("Tomorrow")
                Spacer(Modifier.height(8.dp))
                if (dueTomorrow.isEmpty()) {
                    Text("Nothing due to go out.", style = MaterialTheme.typography.bodyMedium, color = Charcoal.Muted)
                }
                dueTomorrow.forEach { p ->
                    val isCard = p.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtTile(payeeColour(p.rule.payee), size = 38.dp) {
                            if (isCard) Icon(Icons.Filled.CreditCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            else Monogram(p.rule.payee, 38.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.rule.payee.tidyPayee(), style = MaterialTheme.typography.titleSmall)
                            Text(p.rule.cadence.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                        }
                        Text(recurringAmount(p.amountMinor, p.rule.perOccurrence, p.rule.currency, p.rule.isVariable), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            val toCheck = state.duplicateQuestions.size +
                state.seenSpends.count { it.accountId == null && !it.dismissed && it.matchedTransactionId == null } +
                NotificationSpend.unconfirmed(state.seenSpends, System.currentTimeMillis()).size
            if (toCheck > 0) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(BorderStroke(1.dp, accent), RoundedCornerShape(14.dp))
                        .clickable(onClick = onSeeQuestions)
                        .padding(16.dp),
                ) {
                    SectionHeading("$toCheck to check", trailing = "›", colour = accent)
                    Text("Questions about payments, waiting on the Spending tab.", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
                }
            }
        }
    }
}

/**
 * The last seven days as bars, today in white. A day over the day's share of what is left is
 * amber; [perDay] is that share, or null when it is not known.
 */
@Composable
private fun WeekBars(days: List<Long>, today: java.time.LocalDate, perDay: Long?) {
    if (days.isEmpty()) return
    val top = (days.maxOrNull() ?: 0L).coerceAtLeast(perDay ?: 1L).coerceAtLeast(1L)
    val labels = (days.indices).map { i -> today.minusDays((days.size - 1 - i).toLong()) }
    Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, v ->
            val last = i == days.lastIndex
            val colour = when {
                last -> Color.White
                perDay != null && v > perDay -> Charcoal.Warn
                else -> Charcoal.Good
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight((v.toFloat() / top).coerceIn(0.04f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(colour),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, d ->
            Text(
                if (i == labels.lastIndex) "Today" else d.format(WEEKDAY),
                style = MaterialTheme.typography.labelSmall,
                color = Charcoal.Muted,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
