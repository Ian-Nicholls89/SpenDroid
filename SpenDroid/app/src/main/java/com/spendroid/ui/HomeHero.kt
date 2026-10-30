package com.spendroid.ui

import com.spendroid.domain.accountOnly
import com.spendroid.domain.toRecurringRule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.gestures.detectTapGestures
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
    /** With cards, the first page is the month (0.8.0); this is the account it's paid into, on its own. */
    data object Pot : HeroPage
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
    /** Works things out again - after a contract end date is set, or a new price accepted. */
    onReload: () -> Unit = {},
    /** What needs the user, from every tab (0.7.2): Penny says it first, in amber - and where each goes. */
    needsYou: List<com.spendroid.domain.TabVoice.Line> = emptyList(),
    onNeedsYou: (com.spendroid.domain.TabVoice.Target) -> Unit = {},
    /** Which page the hero opens on; for screenshots. */
    initialPage: Int = 0,
    /** Questions waiting on the user, pinned above everything else. */
    prompts: @Composable () -> Unit = {},
    /** Today's allowance, from the hero's "a day". */
    onOpenToday: () -> Unit = {},
    /** Something Penny has noticed, said in place of her line on the day; and following it up. */
    news: PennyNews.News? = null,
    onNews: (PennyNews.News) -> Unit = {},
) {
    val budget = state.budget
    // What Penny could say, recomputed as the figures change - and each half hour, as the
    // day moves from morning to evening.
    val halfHour = java.time.LocalDateTime.now().let { it.toLocalDate().toEpochDay() * 48 + it.hour * 2 + it.minute / 30 }
    // A card has its own page, where Penny says how it's going: not said on the first page too.
    val voice = remember(budget, state.transactions, state.categoryRules, halfHour) {
        pennyLines(state).filter { it.kind != com.spendroid.domain.PennyVoice.Kind.CARD }
    }
    val pages = remember(state.accounts, budget, state.transactions) {
        buildList {
            if (budget != null) add(HeroPage.Budget)
            if (budget != null) {
                if (budget.month != null) add(HeroPage.Pot)
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
    // Next month's statements against next month's money (0.8.0): from Penny's line, or the panel's.
    var nextMonthOpen by remember { mutableStateOf(false) }
    if (nextMonthOpen) budget?.month?.let { NextMonthSheet(it, budget.baseCurrency) { nextMonthOpen = false } }
    val onNeedsYouHere: (com.spendroid.domain.TabVoice.Target) -> Unit = { target ->
        if (target == com.spendroid.domain.TabVoice.Target.NextMonth) nextMonthOpen = true else onNeedsYou(target)
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
                prompts()
            }

            if (budget == null || pages.isEmpty()) {
                NoAccounts(state, onLinkBank)
                return@Column
            }

            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
                when (val p = pages[index]) {
                    HeroPage.Budget -> BudgetHero(budget, state.syncing, onOpenToday, news, onNews, voice, needsYou, onNeedsYouHere)
                    is HeroPage.Account -> AccountHero(p, colourOf(p.account, state.accounts), state.syncing)
                    HeroPage.Pot -> PotHero(budget, state.syncing)
                }
            }
            if (pages.size > 1) PagerDots(pages.size, pager.currentPage, pageColour ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))

            when (page) {
                is HeroPage.Account -> AccountBelow(state, page, colourOf(page.account, state.accounts), onRefresh, onSeeAccount, onOpenForecast)
                HeroPage.Pot -> BudgetBelow(state, budget.accountOnly, onRefresh, onSeeAllTransactions, onOpenForecast, onReload, heading = "From the account")
                else -> BudgetBelow(state, budget, onRefresh, onSeeAllTransactions, onOpenForecast, onReload, onNextMonth = { nextMonthOpen = true })
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
            "Link a bank and Penny will work out your pay cycle from what it finds.",
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
private fun BudgetHero(
    budget: BudgetSnapshot,
    syncing: Boolean,
    onOpenToday: () -> Unit = {},
    news: PennyNews.News? = null,
    onNews: (PennyNews.News) -> Unit = {},
    voice: List<com.spendroid.domain.PennyVoice.Line> = emptyList(),
    needsYou: List<com.spendroid.domain.TabVoice.Line> = emptyList(),
    onNeedsYou: (com.spendroid.domain.TabVoice.Target) -> Unit = {},
) {
    val pace = BudgetPace.of(budget)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        // The droid's read on the day, in a word bubble: the same pace as the bar, said plainly.
        // Something she's noticed takes its place, once.
        if (news != null) {
            PennySays(
                droidFaceOf(budget, syncing), news.line, moment = DroidMoment.NOTICED,
                action = news.ruleKey?.let { "See it" to { onNews(news) } },
            )
        } else if (voice.isNotEmpty() || needsYou.isNotEmpty()) {
            PennyStories(
                droidFaceOf(budget, syncing),
                needsYou.map { it.text } + voice.map { it.text },
                actions = needsYou.map { line -> line.target?.let { linkLabel(it) to { onNeedsYou(it) } } },
                leadByVisit = true,
                needsYou = needsYou.map { true },
            )
        } else {
            PennySays(droidFaceOf(budget, syncing), com.spendroid.domain.TodaysRead.of(budget) { formatMoney(it, budget.baseCurrency) })
        }
        Text(untilLine(budget), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.78f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RollingAmount(
                modifier = Modifier.shimmer(syncing),
                minor = budget.availableToSpend,
                currency = budget.baseCurrency,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, fontFamily = com.spendroid.ui.theme.Grotesk, letterSpacing = (-1.5).sp),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.width(10.dp))
            SyncedTick(syncing)
        }
        val month = budget.month
        Text(
            when {
                month != null -> "left this month, account and cards together"
                budget.budgetModel == BudgetModel.ROLLOVER -> "left to spend, balance carried over"
                else -> "left to spend"
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        // How much of it can come from the account and stay in the black; the rest goes on cards.
        month?.let { m ->
            Text(
                fromAccountLine(m, budget.baseCurrency),
                style = MaterialTheme.typography.labelMedium,
                color = if (m.accountLeftMinor < 0L) Charcoal.Warn else Color(0xFFCFD2D8),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(paceWord(pace), paceColour(pace))
            val days = budget.daysUntilNextIncome
            if (days != null && days > 0) {
                Dot()
                // Today's allowance, a tap away.
                Text(
                    "${formatMoney(budget.availableToSpend / days, budget.baseCurrency)} a day ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFCFD2D8),
                    modifier = Modifier.clickable(onClick = onOpenToday),
                )
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

/**
 * Penny and what she says: at the top of each page of the hero, and wherever she's noticed
 * something - [moment] NOTICED plays her flash once - with [action] a link to follow it up.
 */
@Composable
internal fun PennySays(
    face: DroidFace,
    line: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    moment: DroidMoment = DroidMoment.NONE,
    action: Pair<String, () -> Unit>? = null,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    modifier: Modifier = Modifier.padding(bottom = 12.dp),
    /** Tapping the bubble: on to the next thing she has to say. */
    onTap: (() -> Unit)? = null,
    /** Which of how many lines this is, and how far through its time: bars under it, as stories. */
    story: Story? = null,
    /** Pressed and held, and let go: pauses the story. */
    onHold: ((Boolean) -> Unit)? = null,
    /** Every line she'll say in turn: the bubble is as tall as the longest, so nothing below jumps. */
    sizing: List<String> = emptyList(),
    /** Something for the user to do (0.7.2): the bubble edged in amber, her bulb and the link too. */
    needsYou: Boolean = false,
) {
    // Room above for her sparks and jumps: she's often at the top of something that clips.
    Row(verticalAlignment = Alignment.Top, modifier = modifier.padding(top = size * PENNY_REACH)) {
        Droid(face, size, accent = accent, fillOnOpen = moment == DroidMoment.NONE, moment = moment, bulb = Charcoal.Warn.takeIf { needsYou })
        Spacer(Modifier.width(10.dp))
        val bubble = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)
        Column(
            Modifier
                .clip(bubble)
                .background(Color(0xFF1F2127))
                .then(if (needsYou) Modifier.border(1.5.dp, Charcoal.Warn, bubble) else Modifier)
                .then(
                    if (onTap != null) {
                        Modifier.pointerInput(onTap, onHold) {
                            detectTapGestures(
                                onPress = {
                                    onHold?.invoke(true)
                                    tryAwaitRelease()
                                    onHold?.invoke(false)
                                },
                                onTap = { onTap() },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .animateContentSize(),
        ) {
            // A new line fades in, so a tap reads as her moving on rather than a flicker.
            Box {
                // Unseen, the longest of her lines holds the bubble's size while they change.
                sizing.forEach {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, color = Color.Transparent,
                        // Not read aloud: only the line she's saying is.
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                if (story != null && story.typing) {
                    // Typed out over the first third of the line's time, then left to be read.
                    // Read aloud whole, not as it's typed.
                    val shown = (line.length * minOf(1f, story.progress * 3f)).toInt().coerceIn(0, line.length)
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(line.take(shown))
                            if (shown < line.length) withStyle(androidx.compose.ui.text.SpanStyle(color = if (needsYou) Charcoal.Warn else accent)) { append("▍") }
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE7E9EE),
                        modifier = Modifier.clearAndSetSemantics { contentDescription = line },
                    )
                } else {
                    // A new line fades in, so a tap reads as her moving on rather than a flicker.
                    androidx.compose.animation.Crossfade(line, label = "penny-line") { said ->
                        Text(said, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE7E9EE))
                    }
                }
            }
            // Along the bottom of the bubble - which keeps its size - so they never move: a dot for
            // each line, the one she's saying stretched into a pill that fills over its time.
            story?.let { st ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(st.count) { i ->
                        val width by androidx.compose.animation.core.animateDpAsState(if (i == st.at) 26.dp else 5.dp, label = "dot")
                        // A line that needs the user keeps an amber dot, so it's seen to be waiting
                        // while she says the others.
                        val amber = i in st.amber
                        // Amber whether or not it's the one being said - still, with animations off, it shows.
                        val track = if (amber) Color(0xFF8A6420) else Color(0xFF3A3D45)
                        Box(Modifier.width(width).height(5.dp).clip(RoundedCornerShape(3.dp)).background(track)) {
                            if (i == st.at) Box(Modifier.fillMaxWidth(st.progress).height(5.dp).background(if (amber) Charcoal.Warn else Color(0xFF9AA0AA)))
                        }
                    }
                }
            }
            action?.let { (label, go) ->
                Text(
                    "$label ›",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (needsYou) Charcoal.Warn else accent,
                    modifier = Modifier.padding(top = 6.dp).clickable(onClick = go),
                )
            }
        }
    }
}

/** Where Penny's lines are: [at] of [count], [progress] through its time. */
internal data class Story(val at: Int, val count: Int, val progress: Float, val typing: Boolean = false, val amber: Set<Int> = emptySet())

/** Long enough to read: four seconds, and a little more for each word, up to nine. */
internal fun storyMillis(line: String): Long = (4_000L + line.split(' ').size * 150L).coerceAtMost(9_000L)

/** Penny's face for an account's page: its own pace, its bar on her visor. */
private fun faceOf(row: PeriodRows.Row, syncing: Boolean): DroidFace = DroidFace(
    when {
        syncing -> DroidMood.SYNCING
        else -> when (row.pace) {
            BudgetPace.Pace.ON_TRACK -> DroidMood.AHEAD
            BudgetPace.Pace.TIGHT -> DroidMood.TIGHT
            BudgetPace.Pace.OVER -> DroidMood.OVER
        }
    },
    used = row.used ?: 0f,
    elapsed = row.gone ?: 0.5f,
)

@Composable
private fun AccountHero(page: HeroPage.Account, colour: Color, syncing: Boolean = false) {
    val row = page.row
    val bill = page.bill
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        // Penny on every page: this account's own pace, said plainly, in the account's colour.
        PennySays(faceOf(row, syncing), com.spendroid.domain.TodaysRead.of(row) { formatMoney(it, row.currency) }, colour)
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

/**
 * The account the pay goes into, on its own (0.8.0): what can still leave it before payday and
 * stay in the black, once this month's card statements are paid. The month's first page counts
 * the cards too; this is the limit that keeps the account out of the red.
 */
@Composable
private fun PotHero(month: BudgetSnapshot, syncing: Boolean) {
    val m = month.month ?: return
    val budget = m.account
    val money = { minor: Long -> formatMoney(minor, budget.baseCurrency) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        PennySays(
            droidFaceOf(budget, syncing),
            when {
                budget.shortfallMinor > 0L -> "Your account is ${money(budget.shortfallMinor)} short of what's to come. Keep to the cards."
                budget.availableToSpend == 0L -> "Nothing more from your account before payday. Cards only."
                // The month's money is spent: what the account could still pay is next month's.
                m.leftMinor <= 0L -> "The month's spent. What's still in your account is next month's money."
                // The cards are already past this month's statements: the account could pay more than the month has.
                m.accountLeftMinor > m.leftMinor -> "The month has ${money(m.leftMinor.coerceAtLeast(0L))} left - your account can cover all of it."
                else -> "Up to ${money(budget.availableToSpend)} more from your account. The rest goes on cards."
            },
        )
        Text(untilLine(budget), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.78f))
        RollingAmount(
            modifier = Modifier.shimmer(syncing),
            minor = budget.availableToSpend,
            currency = budget.baseCurrency,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, fontFamily = com.spendroid.ui.theme.Grotesk, letterSpacing = (-1.5).sp),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text("left from your account, in the black", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        if (m.statementsMinor > 0L) {
            Text(
                "After bills and this month's ${money(m.statementsMinor)} of card statements",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFCFD2D8),
                modifier = Modifier.padding(top = 2.dp),
            )
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
private fun BudgetBelow(
    state: RootUiState,
    budget: BudgetSnapshot,
    onRefresh: () -> Unit,
    onSeeAllTransactions: () -> Unit,
    onOpenForecast: (String?) -> Unit = {},
    onReload: () -> Unit = {},
    heading: String? = null,
    /** The month's look ahead at next month's statements, opened (0.8.0). */
    onNextMonth: () -> Unit = {},
) {
    val money = { minor: Long -> formatMoney(minor, budget.baseCurrency) }
    val pace = BudgetPace.of(budget)
    val month = budget.month
    Panel(Modifier.padding(horizontal = 14.dp)) {
        SectionHeading(
            heading ?: if (month != null) "This month" else "This cycle",
            trailing = if (month != null) "account and cards" else "used of budget",
        )
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
        // Where the month's spending went, and the statements it pays (0.8.0).
        month?.let { m ->
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    "${money(m.spentFromAccountMinor)} from your account",
                    "${money(m.spentOnCardsMinor)} on cards",
                    m.cardBillsMinor.takeIf { it > 0L }?.let { "${money(it)} of bills paid by card" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFCFD2D8),
            )
            if (m.statements.isNotEmpty()) {
                Text(
                    "Card statements this month: " + m.statements.joinToString(", ") { "${it.label} ${money(it.amountMinor)}${if (it.paid) " ✓" else ""}" },
                    style = MaterialTheme.typography.labelMedium,
                    color = Charcoal.Muted,
                )
            }
            if (m.nextStatements.isNotEmpty()) {
                val short = m.nextShortMinor
                Text(
                    "Next month's statements: heading for ${money(m.nextStatementsMinor)} · " +
                        if (short > 0L) "${money(short)} short ›" else "covered ›",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (short > 0L) FontWeight.Bold else FontWeight.Normal,
                    color = if (short > 0L) Charcoal.Warn else Charcoal.Muted,
                    modifier = Modifier.padding(top = 2.dp).clickable(onClickLabel = "See next month", onClick = onNextMonth),
                )
            }
        }
        val notes = buildList {
            todayNote(budget)?.let(::add)
            // The account's own: on its page, not the month's (0.8.0).
            if (month == null && budget.budgetModel == BudgetModel.ROLLOVER) budget.potBalanceMinor?.let { add("${money(it)} in the account, less what is due before payday") }
            // Zero cannot say how far past zero, and the difference matters.
            if (budget.shortfallMinor > 0L) {
                add(if (month != null) "${money(budget.shortfallMinor)} over for the month" else "${money(budget.shortfallMinor)} short of covering what is still to come out")
            }
            // The month says it itself, by card and against next month's money.
            if (month == null && budget.cardsNextCycleMinor > 0L) add("About ${money(budget.cardsNextCycleMinor)} of card bills fall in your next cycle")
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
                    "Using the largest income instead; star it again on Regular - tap Income in Each month.",
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
    // A bill that went up once out of contract stays here until it's sorted - a new end date, or
    // the new price accepted.
    val context = androidx.compose.ui.platform.LocalContext.current
    state.outlook.contracts.values.filter { it.status is com.spendroid.domain.Contracts.Status.EndedUp }.forEach { e ->
        Spacer(Modifier.height(12.dp))
        OutOfContractCard(
            e,
            onSetEnd = { date -> com.spendroid.data.ContractEnds(context).set(e.key, date); onReload() },
            onAccept = { com.spendroid.data.ContractEnds(context).accept(e.key, e.status.end); onReload() },
            modifier = Modifier.padding(horizontal = 14.dp),
        )
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
internal fun EachMonthPanel(
    budget: BudgetSnapshot,
    modifier: Modifier = Modifier,
    /** Tapping Income: the income itself, and which sets the pay cycle (0.7.3). */
    onIncome: (() -> Unit)? = null,
) {
    Panel(modifier) {
        SectionHeading("Each month")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (onIncome != null) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "See your income", onClick = onIncome)) {
                    HeroStat("Income ›", budget.averageMonthlyIncome, budget.baseCurrency)
                }
            } else {
                HeroStat("Income", budget.averageMonthlyIncome, budget.baseCurrency)
            }
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

/** How many lines Penny's bubble takes turns with. */
private const val MOST_LINES = 3

/**
 * Penny's lines as stories, Home's way and the tabs': each has its time, read from a bar filling
 * under it, then the next, round and round while it's on screen; a tap moves on, holding pauses.
 * [actions] - one per line, or none - are each line's link to what it's about. With [leadByVisit],
 * a different line leads each time the screen comes to the front.
 */
@Composable
internal fun PennyStories(
    face: DroidFace,
    lines: List<String>,
    actions: List<Pair<String, () -> Unit>?> = emptyList(),
    leadByVisit: Boolean = false,
    modifier: Modifier = Modifier.padding(bottom = 12.dp),
    /** Which of [lines] need the user (0.7.2): said first, and in amber. */
    needsYou: List<Boolean> = emptyList(),
) {
    // What needs the user first, then the rest as they came.
    val order = lines.indices.sortedBy { if (needsYou.getOrElse(it) { false }) 0 else 1 }
    // 0.7.1: the three that matter most now, not every one she has - the rest wait their turn.
    val voice = order.map { lines[it] }.take(MOST_LINES)
    if (voice.isEmpty()) return
    val amber = order.take(MOST_LINES).mapIndexedNotNull { i, at -> i.takeIf { needsYou.getOrElse(at) { false } } }.toSet()
    val doing = order.map { actions.getOrNull(it) }
    // One of her top lines leads, a different one each time Home comes to the front;
    // a tap on her bubble moves on to the next thing she's noticed.
    val context = androidx.compose.ui.platform.LocalContext.current
    var visit by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        visit = PennyVisits.next(context)
        onPauseOrDispose { }
    }
    // Like stories: each line has its time, read from a bar filling under it, then the next,
    // round and round while Home is open. A tap moves on; holding pauses. [step] counts on
    // from the line she led with.
    // With animations off on the phone she waits for a tap instead.
    var step by remember(visit) { androidx.compose.runtime.mutableIntStateOf(0) }
    var held by remember { androidx.compose.runtime.mutableStateOf(false) }
    val progress = remember(visit, step) { androidx.compose.animation.core.Animatable(0f) }
    // Something waiting on the user leads, every time; otherwise her top lines take turns.
    val lead = if (leadByVisit && amber.isEmpty()) com.spendroid.domain.PennyVoice.lead(voice.size, visit) else 0
    // What she had to say as Home opened sets the bubble's size for the visit: the figures
    // changing - a sync - mustn't make it grow and shrink. A longer line since grows it once.
    val opening = remember(visit) { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(voice) } }
    val shown = Math.floorMod(lead + step, voice.size)
    LaunchedEffect(voice[shown]) { if (voice[shown] !in opening) opening += voice[shown] }
    val going = voice.size > 1 && !droidStill()
    LaunchedEffect(visit, step, held, going) {
        if (!going || held) return@LaunchedEffect
        val total = storyMillis(voice[shown])
        progress.animateTo(1f, androidx.compose.animation.core.tween(((1f - progress.value) * total).toInt(), easing = androidx.compose.animation.core.LinearEasing))
        step += 1
    }
    PennySays(
        face, voice[shown],
        action = doing.getOrNull(shown),
        modifier = modifier,
        onTap = { step += 1; Unit }.takeIf { voice.size > 1 },
        onHold = { held = it },
        sizing = (opening + voice[shown]).distinct(),
        // Each time round, the bars start again from the first.
        story = Story(Math.floorMod(step, voice.size), voice.size, progress.value, typing = going, amber = amber).takeIf { voice.size > 1 },
        needsYou = shown in amber,
    )
}

/** How much of the month's figure the account can pay: all of it, some with the rest on cards, or none. */
internal fun fromAccountLine(m: com.spendroid.domain.MonthView, currency: String): String = when {
    m.accountLeftMinor < 0L -> "Your account is ${formatMoney(-m.accountLeftMinor, currency)} short · keep to the cards"
    m.leftMinor <= 0L -> "Nothing left this month, from the account or on cards"
    m.accountLeftMinor >= m.leftMinor -> "Your account can cover all of it"
    else -> "Up to ${formatMoney(m.fromAccountMinor, currency)} from your account · the rest on cards"
}
