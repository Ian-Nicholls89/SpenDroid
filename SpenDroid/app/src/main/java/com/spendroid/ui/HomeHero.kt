package com.spendroid.ui

import com.spendroid.domain.toRecurringRule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CreditCardEngine
import com.spendroid.domain.PeriodRows
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.UpcomingPayment
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")

/** What a page of the hero is about: the budget, or one account the budget does not spend from. */
private sealed interface HeroPage {
    data object Budget : HeroPage
    data class Account(val account: AccountEntity, val row: PeriodRows.Row, val bill: CreditCardEngine.CardBill?) : HeroPage
}

/** The pace's colour and word, shared by every figure that shows it. */
internal fun paceColour(pace: BudgetPace.Pace): Color = when (pace) {
    BudgetPace.Pace.ON_TRACK -> Charcoal.Good
    BudgetPace.Pace.TIGHT -> Charcoal.Warn
    BudgetPace.Pace.OVER -> Charcoal.Bad
}

internal fun paceWord(pace: BudgetPace.Pace): String = when (pace) {
    BudgetPace.Pace.ON_TRACK -> "ON PACE"
    BudgetPace.Pace.TIGHT -> "TIGHT"
    BudgetPace.Pace.OVER -> "OVER PACE"
}

/**
 * The dashboard, in nzb360's manner: a hero you can swipe between the budget and each other
 * account, as nzb360's "Popular Now", then what the page is about - a bar, two big buttons, and
 * what is coming up as a row of posters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onRelink: (Connection) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onSetBudgetGoal: (Category, Long) -> Unit,
    onLinkBank: () -> Unit,
    onSeeAccount: (String) -> Unit = { onSeeAllTransactions() },
    /** The forecast, for an account or - null - the budget's. */
    onOpenForecast: (String?) -> Unit = {},
    /** Which page the hero opens on; for screenshots. */
    initialPage: Int = 0,
) {
    val budget = state.budget
    val pages = remember(state.accounts, budget, state.transactions) {
        buildList {
            if (budget != null) add(HeroPage.Budget)
            if (budget != null) {
                // Cards first - their bills are the biggest thing to come - then the rest.
                val others = state.accounts
                    .filter { it.id != budget.potAccountId && it.accountType != AccountType.PAYPAL }
                    .sortedBy { if (it.accountType == AccountType.CREDIT_CARD) 0 else 1 }
                PeriodRows.rows(others.map { it.id }, budget, state.accounts, state.transactions).forEach { row ->
                    val account = others.first { it.id == row.accountId }
                    // A personal account the budget is paid from has no page of its own: it is the budget.
                    if (row.kind == PeriodRows.Kind.BUDGET) return@forEach
                    add(HeroPage.Account(account, row, budget.cardBills.firstOrNull { it.cardAccountId == account.id }))
                }
            }
        }
    }
    val pager = rememberPagerState(initialPage) { pages.size.coerceAtLeast(1) }
    val page = pages.getOrNull(pager.currentPage)
    val pageColour = when (page) {
        is HeroPage.Account -> colourOf(page.account, state.accounts)
        else -> null
    }
    // The glow and wordmark take the page's colour, and give it back on leaving.
    val glow = LocalGlow.current
    LaunchedEffect(pageColour) { glow.colour = pageColour }
    DisposableEffect(Unit) { onDispose { glow.colour = null } }

    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.bankHolidayToday?.let { BankHolidayBanner(it) }
                budget?.unconvertedCurrencies?.takeIf { it.isNotEmpty() }?.let { ForeignCurrencyBanner(it) }
                if (state.reauthNeeded.isNotEmpty()) ReauthBanner(state.reauthNeeded, onRelink)
            }

            if (budget == null || pages.isEmpty()) {
                NoAccounts(state, onLinkBank)
                return@Column
            }

            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
                when (val p = pages[index]) {
                    HeroPage.Budget -> BudgetHero(budget, state.syncing)
                    is HeroPage.Account -> AccountHero(p, colourOf(p.account, state.accounts))
                }
            }
            if (pages.size > 1) PagerDots(pages.size, pager.currentPage, pageColour ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))

            when (page) {
                is HeroPage.Account -> AccountBelow(state, page, colourOf(page.account, state.accounts), onRefresh, onSeeAccount, onOpenForecast)
                else -> BudgetBelow(state, budget, onRefresh, onSeeAllTransactions, onOpenForecast)
            }

            state.linkProgress?.let { progress ->
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
            }
            // What the last pull-to-refresh did about the bank's allowance.
            state.syncNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
        }
    }
}

@Composable
private fun NoAccounts(state: RootUiState, onLinkBank: () -> Unit) {
    if (state.accounts.isNotEmpty()) return
    Panel(Modifier.padding(14.dp)) {
        SectionHeading("No accounts yet")
        Spacer(Modifier.height(6.dp))
        Text(
            "Link a bank and SpenDroid will work out your pay cycle from what it finds.",
            style = MaterialTheme.typography.bodyMedium,
            color = Charcoal.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onLinkBank) { Text("Link a bank") }
    }
}

/** The line above the figure: when payday is, or that it has come and the salary has not. */
private fun untilLine(budget: BudgetSnapshot): String {
    val next = budget.nextIncomeDate ?: return "Your budget"
    val today = budget.asOf
    val days = ChronoUnit.DAYS.between(today, next).coerceAtLeast(0)
    return when {
        next == today -> "Payday today · the new cycle starts when it clears"
        next.isBefore(today) -> "Income expected ${next.format(DAY)} · not cleared yet"
        else -> "Until payday · ${next.format(DAY)} · $days day${if (days == 1L) "" else "s"}"
    }
}

@Composable
private fun BudgetHero(budget: BudgetSnapshot, syncing: Boolean) {
    val pace = BudgetPace.of(budget)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(untilLine(budget), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.78f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RollingAmount(
                modifier = Modifier.shimmer(syncing),
                minor = budget.availableToSpend,
                currency = budget.baseCurrency,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 46.sp),
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            Spacer(Modifier.width(10.dp))
            SyncedTick(syncing)
        }
        Text(
            if (budget.budgetModel == BudgetModel.ROLLOVER) "left to spend, balance carried over" else "left to spend",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(paceWord(pace), paceColour(pace))
            val days = budget.daysUntilNextIncome
            if (days != null && days > 0) {
                Dot()
                Text("${formatMoney(budget.availableToSpend / days, budget.baseCurrency)} a day", style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8))
            }
            dayOfCycle(budget)?.let {
                Dot()
                Text(it, style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8))
            }
        }
    }
}

@Composable
private fun Dot() = Text("•", color = Color(0xFF8D929C))

private fun dayOfCycle(budget: BudgetSnapshot): String? {
    val end = budget.cycleEnd ?: return null
    val total = ChronoUnit.DAYS.between(budget.cycleStart, end).toInt() + 1
    val day = ChronoUnit.DAYS.between(budget.cycleStart, budget.asOf).toInt() + 1
    return if (total > 0 && day in 1..total) "day $day of $total" else null
}

@Composable
private fun AccountHero(page: HeroPage.Account, colour: Color) {
    val row = page.row
    val bill = page.bill
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            "${page.account.label} · ${if (row.kind == PeriodRows.Kind.STATEMENT) "this statement" else "this pay cycle"}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.78f),
        )
        Text(
            "${formatMoney(row.spentMinor, row.currency)} so far",
            fontFamily = Lato,
            fontWeight = FontWeight.Black,
            fontSize = 38.sp,
            color = Color.White,
        )
        Text(
            when {
                row.kind != PeriodRows.Kind.STATEMENT -> "spent from this account this pay cycle"
                // Known payments still to come on the card, from the statement's first day.
                row.toComeMinor > 0L ->
                    "spent this statement · ${formatMoney(row.toComeMinor, row.currency)} to come" +
                        (bill?.nextStatementClose?.let { " by ${it.format(dateFormat)}" } ?: "")
                else -> "spent on the card since the last statement"
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.againstMinor?.let { against ->
                val whose = when (row.against) {
                    PeriodRows.Against.LIMIT -> "LIMIT"
                    else -> "USUAL"
                }
                val over = row.pace == BudgetPace.Pace.OVER
                Chip(if (over) "OVER $whose" else "BELOW $whose", if (over) Charcoal.Bad else Charcoal.Good)
                Dot()
                Text("${whose.lowercase()} ${formatMoney(against, row.currency)}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8))
            }
            bill?.statementClose?.let { close ->
                if (row.againstMinor != null) Dot()
                Text("closed ${close.format(dateFormat)}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8))
            }
            if (row.againstMinor == null && bill == null && row.day != null && row.days != null) {
                Text("day ${row.day} of ${row.days}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8))
            }
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int, colour: Color) {
    Row(Modifier.padding(start = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .width(if (i == current) 22.dp else 16.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i == current) colour else Color(0xFF555A63)),
            )
        }
    }
}

@Composable
private fun BudgetBelow(state: RootUiState, budget: BudgetSnapshot, onRefresh: () -> Unit, onSeeAllTransactions: () -> Unit, onOpenForecast: (String?) -> Unit = {}) {
    val money = { minor: Long -> formatMoney(minor, budget.baseCurrency) }
    val pace = BudgetPace.of(budget)
    Panel(Modifier.padding(horizontal = 14.dp)) {
        SectionHeading("This cycle", trailing = "used of budget")
        Spacer(Modifier.height(10.dp))
        val against = budget.spendableThisCycle
        LabelledBar(
            fraction = BudgetPace.usedFraction(budget) ?: 0f,
            label = if (against > 0L) "${money(budget.usedThisCycle)} / ${money(against)}" else money(budget.spentThisCycle),
            colour = paceColour(pace),
            tick = BudgetPace.elapsedFraction(budget),
        )
        paceCaption(budget)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            HeroStat("Spent today", budget.spentTodayFromAccountsMinor + budget.spentTodayOnCardsMinor, budget.baseCurrency)
            HeroStat("This cycle", budget.spentThisCycle, budget.baseCurrency)
            // Beside the budget rather than folded into it: the gap between them is the point.
            if (budget.budgetModel == BudgetModel.SHOW_BOTH) budget.potBalanceMinor?.let { HeroStat("In the account", it, budget.baseCurrency) }
        }
        val notes = buildList {
            todayNote(budget)?.let(::add)
            if (budget.budgetModel == BudgetModel.ROLLOVER) budget.potBalanceMinor?.let { add("${money(it)} in the account, less what is due before payday") }
            // Zero cannot say how far past zero, and the difference matters.
            if (budget.shortfallMinor > 0L) add("${money(budget.shortfallMinor)} short of covering what is still to come out")
            if (budget.cardsNextCycleMinor > 0L) add("About ${money(budget.cardsNextCycleMinor)} of card bills fall in your next cycle")
            // Held back towards bills that come quarterly or yearly, so the cycle they land in is not hit.
            if (budget.setAsideMinor > 0L) {
                val n = budget.setAside.size
                add("${money(budget.setAsideMinor)} set aside towards $n quarterly or yearly bill${if (n == 1) "" else "s"}")
            }
        }
        if (notes.isNotEmpty()) Spacer(Modifier.height(8.dp))
        notes.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted) }
        if (budget.designationLost) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The income you picked to set the cycle is no longer being detected - possibly renamed by your bank. " +
                    "Using the largest income instead; star it again under Regular, on the Income tab.",
                style = MaterialTheme.typography.labelMedium,
                color = Charcoal.Warn,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("Transactions", Icons.AutoMirrored.Filled.ReceiptLong, onSeeAllTransactions, Modifier.weight(1f))
        BigButton("Refresh", Icons.Filled.Refresh, onRefresh, Modifier.weight(1f))
    }
    // Any account heading below zero in the next month, then where the budget's account is heading.
    state.outlook.warnings.forEach { w ->
        Spacer(Modifier.height(12.dp))
        HeadsUpCard(w, onOpen = { onOpenForecast(w.account.id) }, modifier = Modifier.padding(horizontal = 14.dp))
    }
    state.outlook.ahead?.let { ahead ->
        Spacer(Modifier.height(12.dp))
        AheadPanel(ahead, state.outlook.payday, budget.baseCurrency, onOpen = { onOpenForecast(ahead.accountId) }, modifier = Modifier.padding(horizontal = 14.dp))
    }
    if (budget.upcomingFixed.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        AccentTitle("Coming up", "before payday · ${money(budget.upcomingFixed.sumOf { it.amountMinor })}")
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(budget.upcomingFixed, key = { "${it.rule.key}|${it.dueDate}" }) { payment -> UpcomingPoster(payment, state, budget) }
        }
    }
    // "Each month" heads Regular, where its figures come from, and "Where you stand" heads
    // Accounts: Home is about this cycle.
}

/**
 * The month as the regular payments make it: income, bills, and what that leaves to spend, with
 * a bar for how much of the income the bills take. At the head of Regular.
 */
@Composable
internal fun EachMonthPanel(budget: BudgetSnapshot, modifier: Modifier = Modifier) {
    Panel(modifier) {
        SectionHeading("Each month")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            HeroStat("Income", budget.averageMonthlyIncome, budget.baseCurrency)
            HeroStat("Bills", budget.fixedMonthlyOutgoings, budget.baseCurrency)
            Column {
                Text("To spend", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Text(formatMoney(budget.variableMonthlyBudget, budget.baseCurrency), style = MaterialTheme.typography.titleMedium, color = InColor)
            }
        }
        if (budget.averageMonthlyIncome > 0L) {
            val share = budget.fixedMonthlyOutgoings.toFloat() / budget.averageMonthlyIncome
            Spacer(Modifier.height(10.dp))
            LabelledBar(
                fraction = share,
                label = "${(share * 100).toInt()}% of income on bills",
                colour = if (share > 0.8f) Charcoal.Bad else if (share > 0.6f) Charcoal.Warn else Charcoal.Good,
                height = 18.dp,
            )
        }
        if (budget.incomeRules.isNotEmpty() || budget.fixedRules.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "From ${budget.incomeRules.size} regular ${if (budget.incomeRules.size == 1) "income" else "incomes"} " +
                    "and ${budget.fixedRules.size} regular ${if (budget.fixedRules.size == 1) "payment" else "payments"}.",
                style = MaterialTheme.typography.labelMedium,
                color = Charcoal.Muted,
            )
        }
    }
}

/** A bill to come as a poster: its date on the chip, a card bill in the card's colour. */
@Composable
private fun UpcomingPoster(payment: UpcomingPayment, state: RootUiState, budget: BudgetSnapshot) {
    val isCard = payment.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
    val cardAccount = if (isCard) state.accounts.firstOrNull { a -> budget.cardBills.any { it.cardAccountId == a.id && payment.rule.payee.contains(a.label) } } else null
    val colour = when {
        isCard -> colourOf(cardAccount, state.accounts)
        else -> payeeColour(payment.rule.payee)
    }
    PosterTile(
        title = payment.rule.payee.tidyPayee(),
        subtitle = recurringAmount(payment.amountMinor, payment.rule.perOccurrence, payment.rule.currency, payment.rule.isVariable),
        chip = (if (isCard) "~" else "") + shortDay(payment.dueDate, budget.asOf),
        colour = colour,
        glyph = {
            if (isCard) Icon(Icons.Filled.CreditCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            else Monogram(payment.rule.payee.tidyPayee(), 52.dp)
        },
    )
}

/** "Today", "Tomorrow", else "5 Oct". */
internal fun shortDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(dateFormat)
}

/** A steady colour for a payee with no category of its own to colour it. */
internal fun payeeColour(payee: String): Color {
    val palette = listOf(0xFF7A3BE0, 0xFFE05A3B, 0xFF1B9BD8, 0xFF2FB57A, 0xFFE04A7A, 0xFFB8A12D, 0xFF5A5FD8, 0xFF2FB5A5)
    val key = payee.lowercase().filter { it.isLetter() }
    return Color(palette[Math.floorMod(key.hashCode(), palette.size)])
}

@Composable
private fun AccountBelow(state: RootUiState, page: HeroPage.Account, colour: Color, onRefresh: () -> Unit, onSeeAccount: (String) -> Unit, onOpenForecast: (String?) -> Unit = {}) {
    val row = page.row
    val bill = page.bill
    Panel(Modifier.padding(horizontal = 14.dp)) {
        val heading = if (row.kind == PeriodRows.Kind.STATEMENT) "This statement" else "This pay cycle"
        val dayNote = if (row.day != null && row.days != null) "day ${row.day} of ${row.days}" else null
        SectionHeading(heading, trailing = dayNote)
        Spacer(Modifier.height(10.dp))
        val against = row.againstMinor
        LabelledBar(
            fraction = row.used ?: 0f,
            label = if (against != null) "${formatMoney(row.spentMinor, row.currency)} / ${formatMoney(against, row.currency)}" else formatMoney(row.spentMinor, row.currency),
            colour = colour,
            tick = row.gone,
            extra = against?.takeIf { it > 0L }?.let { row.toComeMinor.toFloat() / it } ?: 0f,
        )
        val note = bill?.let { cardPaceLine(it)?.text }
            ?: against?.let { "Measured against what this account usually spends by now" }
        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
        }
        if (row.pendingMinor > 0L) {
            Text("incl. ${formatMoney(row.pendingMinor, row.currency)} pending", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton(
            if (page.account.accountType == AccountType.CREDIT_CARD) "Card spending" else "Transactions",
            Icons.AutoMirrored.Filled.ReceiptLong,
            { onSeeAccount(page.account.id) },
            Modifier.weight(1f),
        )
        BigButton("Refresh", Icons.Filled.Refresh, onRefresh, Modifier.weight(1f))
    }
    // Where this account is heading, to payday - for money held, not a card.
    val ahead = remember(page.account, state.rules, state.manualRules, state.ignoredRules, state.budget, state.transactions) {
        if (page.account.accountType == AccountType.CREDIT_CARD || page.account.accountType == AccountType.PAYPAL) null
        else com.spendroid.domain.Forecast.forAccount(
            page.account, state.accounts,
            (state.rules + state.manualRules.mapNotNull { it.toRecurringRule() }).filter { it.key !in state.ignoredRules },
            state.ignoredRules, state.budget, state.transactions, java.time.LocalDate.now(),
            (state.budget?.nextIncomeDate ?: java.time.LocalDate.now().plusDays(30)).plusDays(1),
            com.spendroid.domain.WorkingDayCalendar(state.bankHolidays), state.ruleOverrides,
        )
    }
    ahead?.let {
        Spacer(Modifier.height(12.dp))
        AheadPanel(it, state.budget?.nextIncomeDate, page.account.currency, onOpen = { onOpenForecast(page.account.id) }, modifier = Modifier.padding(horizontal = 14.dp))
    }
    val since = if (row.kind == PeriodRows.Kind.STATEMENT) bill?.statementClose else state.budget?.cycleStart
    val recent = remember(state.transactions, page.account.id, since) {
        state.transactions
            .filter { tx ->
                tx.accountId == page.account.id && tx.amountMinor < 0 && !tx.isInternalTransfer &&
                    (since == null || RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.isAfter(since) == true)
            }
            .take(12)
    }
    if (recent.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        AccentTitle(
            if (page.account.accountType == AccountType.CREDIT_CARD) "On this card" else "From this account",
            if (row.kind == PeriodRows.Kind.STATEMENT) "this statement" else "this pay cycle",
            colour = colour,
            onMore = { onSeeAccount(page.account.id) },
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recent, key = { it.transactionId }) { tx -> SpendPoster(tx, state) }
        }
    }
    bill?.let {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 14.dp)) { StatementsPanel(it, colour) }
    }
}

/** A recent payment as a poster in its category's colour, pending ones named in blue. */
@Composable
private fun SpendPoster(tx: TransactionEntity, state: RootUiState) {
    val category = CategoryEngine.classify(tx, state.categoryRules, state.budget?.cardPaymentKeys.orEmpty(), state.budget?.creditCardAccountIds.orEmpty())
    val today = state.budget?.asOf ?: LocalDate.now()
    val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate)
    PosterTile(
        title = tx.payee.tidyPayee().ifBlank { "Unknown" },
        subtitle = formatMoney(-tx.amountMinor, tx.currency) + if (tx.isPending) " pending" else "",
        chip = date?.let { if (ChronoUnit.DAYS.between(it, today) in 2..6) it.format(WEEKDAY) else shortDay(it, today).replace("Tomorrow", it.format(dateFormat)) },
        colour = category.visual.color,
        titleColour = if (tx.isPending) Color(0xFF29B6F6) else Color.White,
        glyph = { Icon(category.visual.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp)) },
    )
}
