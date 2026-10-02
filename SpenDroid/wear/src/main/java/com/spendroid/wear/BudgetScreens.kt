package com.spendroid.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.VerticalPagerScaffold
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The watch app, in Google's Material 3 for Wear OS: black screens with colour only as an accent -
 * black pixels are off on an OLED screen - the curved clock, page dots, watch-sized type, and an
 * edge button along the bottom curve. Only the budget and each card take pace colours.
 */

private val Green = Color(0xFFC4E84A)
private val Red = Color(0xFFFF5C3C)
private val Amber = Color(0xFFFFB74D)
private val Blue = Color(0xFFBED8FF)
private val Track = Color(0xFF2D303A)
private val Muted = Color(0xFFA0AABE)
private val Pending = Color(0xFFFFD696)
private val Panel = Color(0xFF161A22)

private sealed interface Page {
    data object Hero : Page
    data object Today : Page
    data object Categories : Page
    data object Recent : Page
    data object Week : Page
    data class Card(val card: BudgetReading.Card) : Page
    data object Upcoming : Page
    data class Update(val name: String, val url: String) : Page
    data object Phone : Page
}

private fun paceColour(pace: String) = when (pace) {
    "OVER" -> Red
    "TIGHT" -> Amber
    else -> Green
}

@Composable
fun BudgetScreens(
    reading: BudgetReading?,
    onOpenPhone: () -> Boolean,
    roundup: Roundup? = null,
    onRoundupShown: () -> Unit = {},
    /** Which page the pager opens on; for screenshots. */
    initialPage: Int = 0,
) {
    MaterialTheme {
        AppScaffold {
            if (reading == null || reading.map.getString("availableFull") == null) {
                ScreenScaffold {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Open SpenDroid on your phone and pull to refresh, and the figures will arrive here.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                return@AppScaffold
            }
            // The pages turn with the crown; a list long enough to scroll opens on a screen of its
            // own, where the crown scrolls it, and a swipe to the right comes back.
            val nav = rememberSwipeDismissableNavController()
            // Opened from the roundup's icon: the roundup first, and a swipe back to the pages.
            var shownRoundup by remember { mutableStateOf<Roundup?>(null) }
            androidx.compose.runtime.LaunchedEffect(roundup) {
                if (roundup != null) {
                    shownRoundup = roundup
                    onRoundupShown()
                    if (nav.currentDestination?.route != "roundup") nav.navigate("roundup")
                }
            }
            SwipeDismissableNavHost(navController = nav, startDestination = "pages") {
                composable("pages") {
                    Pages(
                        reading,
                        initialPage = initialPage,
                        onSeeRecent = { nav.navigate("recent") },
                        onSeeUpcoming = { nav.navigate("upcoming") },
                        onOpenPhone = onOpenPhone,
                    )
                }
                composable("recent") { RecentList(reading) { index -> nav.navigate("pick/$index") } }
                composable("upcoming") { UpcomingList(reading) }
                composable("roundup") { shownRoundup?.let { RoundupScreen(it, reading) } ?: nav.popBackStack() }
                composable("pick/{index}") { entry ->
                    val recent = entry.arguments?.getString("index")?.toIntOrNull()?.let { reading.recent.getOrNull(it) }
                    if (recent == null) {
                        nav.popBackStack()
                    } else {
                        CategoryPicker(reading, recent, onDone = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

@Composable
private fun Pages(
    reading: BudgetReading,
    initialPage: Int = 0,
    onSeeRecent: () -> Unit,
    onSeeUpcoming: () -> Unit,
    onOpenPhone: () -> Boolean,
) {
    var openedOnPhone by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pages = buildList {
        add(Page.Hero)
        add(Page.Today)
        if (reading.categories.isNotEmpty()) add(Page.Categories)
        if (reading.recent.isNotEmpty()) add(Page.Recent)
        add(Page.Week)
        reading.cards.forEach { add(Page.Card(it)) }
        add(Page.Upcoming)
        // A newer watch app, when the phone knows of one: installed from here, not over ADB.
        WatchUpdater.available(context, reading)?.let { (name, url) -> add(Page.Update(name, url)) }
        add(Page.Phone)
    }
    val state = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
    VerticalPagerScaffold(pagerState = state) {
        VerticalPager(state = state, modifier = Modifier.fillMaxSize()) { index ->
            AnimatedPage(pageIndex = index, pagerState = state) {
                ScreenScaffold {
                    when (val page = pages[index]) {
                        Page.Hero -> Hero(reading)
                        Page.Today -> Today(reading)
                        Page.Categories -> Categories(reading)
                        Page.Recent -> RecentSummary(reading, onSeeRecent)
                        Page.Week -> Week(reading)
                        is Page.Card -> CardScreen(page.card)
                        Page.Upcoming -> UpcomingSummary(reading, onSeeUpcoming)
                        is Page.Update -> UpdatePage(page.name, page.url)
                        Page.Phone -> Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                            // Only the button, as asked: a circle filling the screen, its label centred.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(Color(reading.accent))
                                    .clickable { if (onOpenPhone()) openedOnPhone = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Open on phone",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color(0xFF111111),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val style = OpenOnPhoneDialogDefaults.curvedTextStyle
    val text = OpenOnPhoneDialogDefaults.text
    OpenOnPhoneDialog(
        visible = openedOnPhone,
        onDismissRequest = { openedOnPhone = false },
        curvedText = { openOnPhoneDialogCurvedText(text, style) },
    )
}

@Composable
private fun Centre(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Badge(text: String, colour: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.Black,
        modifier = Modifier.background(colour, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

private const val ARC_START = 128f
private const val ARC_SWEEP = 284f

private fun DrawScope.arc(from: Float, sweep: Float, color: Color, width: Float) {
    if (sweep <= 0f) return
    val inset = width / 2 + 4.dp.toPx()
    drawArc(
        color = color, startAngle = from, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

/** 1 · The figure, as the phone's Home: the pace chip, the daily allowance, and the cycle as a bar. */
@Composable
private fun Hero(r: BudgetReading) {
    val accent = Color(r.accent)
    NzbScreen("SpenDroid", accent) {
        Centre {
            Spacer(Modifier.height(16.dp))
            Text(r.text("availableFull"), style = MaterialTheme.typography.numeralMedium, maxLines = 1)
            WatchChip(paceWord(r.pace), pace(r.pace))
            Spacer(Modifier.height(6.dp))
            r.used?.let { used ->
                WatchBar(used, r.text("barLabel"), pace(r.pace), Modifier.padding(horizontal = 6.dp), tick = r.elapsed)
            }
            Spacer(Modifier.height(4.dp))
            Text(r.text("daysLine"), style = MaterialTheme.typography.labelSmall, color = Soft, maxLines = 1)
        }
    }
}

@Composable
private fun Line(label: String, value: String, note: String = "") {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
    if (note.isNotEmpty()) {
        Text(note, style = MaterialTheme.typography.labelSmall, color = Pending, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(4.dp))
}

/** 2 · Today: what went out, and from where - accounts and cards, each a bar in its colour. */
@Composable
private fun Today(r: BudgetReading) {
    NzbScreen("Today", Color(r.accent), glow = 0.35f) {
        Centre {
            Spacer(Modifier.height(8.dp))
            Text(r.text("todayTotal").ifEmpty { r.text("todayAccounts") }, style = MaterialTheme.typography.numeralSmall, maxLines = 1)
            Text("spent today", style = MaterialTheme.typography.labelSmall, color = Soft, maxLines = 1)
            r.text("todayPending").takeIf { it.isNotEmpty() }?.let {
                Text("incl. $it", style = MaterialTheme.typography.labelSmall, color = PendingBlue, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            val accounts = r.map.getLong("todayAccountsMinor")
            val cards = r.map.getLong("todayCardsMinor")
            val top = maxOf(accounts, cards, 1L).toFloat()
            SplitLine("Accounts", r.text("todayAccounts"), accounts / top, Color(r.map.getInt("potColour", r.accent)))
            Spacer(Modifier.height(6.dp))
            SplitLine("Cards", r.text("todayCards"), cards / top, Color(r.map.getInt("cardsColour", r.accent)))
        }
    }
}

@Composable
private fun SplitLine(label: String, value: String, share: Float, colour: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(2.dp))
        WatchBar(share, "", colour, height = 9.dp)
    }
}

/** 6 · Most spent: the leading category's tile, and the top four beside it in the accent. */
@Composable
private fun Categories(r: BudgetReading) {
    val leader = r.categories.firstOrNull()
    NzbScreen("Most spent", Color(leader?.colour ?: r.accent), glow = 0.4f) {
        Centre {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                leader?.let { WatchTile(initial(it.label), Color(it.colour), size = 40.dp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    r.categories.take(4).forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1} ${c.label}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (i == 0) FontWeight.Bold else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                c.spent.replace(Regex("[.,]\\d\\d$"), ""),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = if (c.over) Bad else Color(r.accent),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("this cycle", style = MaterialTheme.typography.labelSmall, color = Soft)
        }
    }
}

/** 5 · Recent: the latest two as the phone's cards, under a day heading, and the way into the rest. */
@Composable
private fun RecentSummary(r: BudgetReading, onSeeAll: () -> Unit) {
    NzbScreen("Recent", Color(r.accent), glow = 0.25f) {
        Centre {
            Spacer(Modifier.height(18.dp))
            r.recent.take(2).forEach { t ->
                RecentRow(t)
                Spacer(Modifier.height(3.dp))
            }
            Spacer(Modifier.height(4.dp))
            SeeAll("See all ${r.recent.size}", onSeeAll)
        }
    }
}

/** A payment as a small card: its category tile, its name, the amount and "Pending" in blue. */
@Composable
private fun RecentRow(t: BudgetReading.Recent, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Charcoal)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WatchTile(initial(t.payee), Color(t.colour), size = 24.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(t.payee, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.pending) Text("Pending", style = MaterialTheme.typography.labelSmall, color = PendingBlue, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        Text(t.amount, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun SeeAll(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Charcoal, contentColor = Color.White),
    ) { Text("$label ›", maxLines = 1) }
}

/** Every recent payment, scrolled with the crown, under day headings; tap one to change its category. */
@Composable
private fun RecentList(r: BudgetReading, onPick: (Int) -> Unit) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Recent", color = Color(r.accent)) } }
            items(r.recent.size) { index ->
                val t = r.recent[index]
                Column(Modifier.fillMaxWidth()) {
                    if (t.day.isNotEmpty() && (index == 0 || r.recent[index - 1].day != t.day)) {
                        Text(t.day, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(r.accent), modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                    }
                    RecentRow(t) { onPick(index) }
                }
            }
            item {
                Text(
                    "Tap one to change its category",
                    style = MaterialTheme.typography.labelSmall,
                    color = Soft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Every category, as buttons; the choice goes to the phone, which sends the new figures back. */
@Composable
private fun CategoryPicker(r: BudgetReading, recent: BudgetReading.Recent, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(recent.payee, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            items(r.categoryOptions) { option ->
                Button(
                    onClick = {
                        scope.launch {
                            val sent = PhoneLink.setCategory(context, recent.id, option.name)
                            android.widget.Toast.makeText(
                                context,
                                if (sent) "${option.label} - sent to your phone" else "Couldn't reach your phone",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Set explicitly: the theme's default text is for light buttons, and was dark on dark.
                    colors = if (option.label == recent.category) {
                        ButtonDefaults.buttonColors(containerColor = Color(option.colour), contentColor = Color.Black, iconColor = Color.Black)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White, iconColor = Color(option.colour))
                    },
                    icon = { Box(Modifier.size(10.dp).background(Color(option.colour), CircleShape)) },
                ) { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Muted),
                ) { Text("Cancel") }
            }
        }
    }
}

/** The last seven days as bars: today in white, a day over its share in amber. */
@Composable
private fun Week(r: BudgetReading) {
    val values = r.week
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val end = runCatching { LocalDate.parse(r.text("weekEnds")) }.getOrNull()
    val usual = values.dropLast(1).takeIf { it.isNotEmpty() }?.average()
    NzbScreen("This week", Color(r.accent), glow = 0.3f) {
        Centre {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                values.forEachIndexed { i, v ->
                    val colour = when {
                        i == values.lastIndex -> Color.White
                        usual != null && v > usual * 1.5 -> Warn
                        else -> Good
                    }
                    Box(Modifier.width(12.dp).fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.05f)).background(colour, RoundedCornerShape(4.dp)))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                values.indices.forEach { i ->
                    val day = end?.minusDays((values.size - 1 - i).toLong())?.dayOfWeek?.name?.take(1).orEmpty()
                    Text(
                        day,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == values.lastIndex) Color.White else Soft,
                        fontWeight = if (i == values.lastIndex) FontWeight.Bold else null,
                        modifier = Modifier.width(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(r.text("updated"), style = MaterialTheme.typography.labelSmall, color = Soft)
        }
    }
}

/** 3 · A card, in its own colour: its statement as a bar against the usual bill, and the next bill. */
@Composable
private fun CardScreen(c: BudgetReading.Card) {
    val colour = Color(c.colour)
    NzbScreen(c.text("name"), colour, glow = 0.55f) {
        Centre {
            Spacer(Modifier.height(16.dp))
            Text(c.text("since"), style = MaterialTheme.typography.numeralSmall, maxLines = 1)
            if (c.capShare != null) WatchChip(if (c.over) "OVER USUAL" else "BELOW USUAL", if (c.over) Bad else Good)
            Spacer(Modifier.height(6.dp))
            c.capShare?.let { share ->
                WatchBar(
                    share,
                    "${c.text("since").replace(Regex("[.,]\\d\\d$"), "")} / ${c.text("capShort")}",
                    colour,
                    Modifier.padding(horizontal = 6.dp),
                    tick = c.statementGone,
                )
                Spacer(Modifier.height(4.dp))
            }
            c.text("nextBill").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Soft, maxLines = 1) }
                ?: c.text("closes").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Soft, maxLines = 1) }
            c.text("pending").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = PendingBlue, maxLines = 1) }
        }
    }
}

/** 4 · Coming up: the next three bills as posters, the total before payday, and the full list. */
@Composable
private fun UpcomingSummary(r: BudgetReading, onSeeAll: () -> Unit) {
    NzbScreen("Coming up", Color(r.accent), glow = 0.35f) {
        Centre {
            Spacer(Modifier.height(30.dp))
            val items = r.upcoming
            if (items.isEmpty()) {
                Text("Nothing else before payday", style = MaterialTheme.typography.bodySmall, color = Soft, textAlign = TextAlign.Center)
                return@Centre
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items.take(2).forEach { u ->
                    WatchPoster(
                        name = u.name,
                        amount = u.short,
                        date = u.date.removePrefix("~"),
                        colour = Color(u.colour),
                        glyph = if (u.card) "▭" else initial(u.name),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SeeAll("${r.text("upcomingTotal")} · all ${items.size}", onSeeAll)
        }
    }
}

/** Everything to come before payday, scrolled with the crown, each with its tile and date. */
@Composable
private fun UpcomingList(r: BudgetReading) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Coming up", color = Color(r.accent)) } }
            items(r.upcoming) { u ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Charcoal).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WatchTile(if (u.card) "▭" else initial(u.name), Color(u.colour), size = 24.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(u.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(u.amount, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
                        Text(u.date, style = MaterialTheme.typography.labelSmall, color = Color(r.accent))
                    }
                }
            }
            item {
                Text("${r.text("upcomingTotal")} in all", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 7 · The roundup, from the watch-face icon: the figure and pace, the week, and what is next. */
@Composable
private fun RoundupScreen(r: Roundup, reading: BudgetReading?) {
    val accent = Color(reading?.accent ?: 0xFFF5A623.toInt())
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to accent.copy(alpha = 0.5f), 0.45f to Color.Transparent)))
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("ROUNDUP", color = accent, fontWeight = FontWeight.Black) } }
            item {
                Text(r.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth())
            }
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WatchChip(paceWord(r.pace), pace(r.pace)) } }
            reading?.week?.takeIf { it.isNotEmpty() }?.let { values ->
                item {
                    val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
                    val usual = values.dropLast(1).takeIf { it.isNotEmpty() }?.average()
                    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        values.forEachIndexed { i, v ->
                            val colour = when {
                                i == values.lastIndex -> Color.White
                                usual != null && v > usual * 1.5 -> Warn
                                else -> Good
                            }
                            Box(Modifier.width(9.dp).fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.06f)).background(colour, RoundedCornerShape(3.dp)))
                        }
                    }
                }
            }
            items(r.lines) { line ->
                // Tomorrow's bills and anything to check stand out in outlined cards, as the phone's.
                val outlined = line.startsWith("Tomorrow") || line.contains("to check")
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (outlined) FontWeight.Black else null,
                    color = if (outlined) accent else Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (outlined) Modifier.border(1.dp, accent, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp) else Modifier),
                )
            }
        }
    }
}

/** A newer watch app: downloaded here, and installed once the system's prompt is confirmed. */
@Composable
private fun UpdatePage(name: String, url: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Centre {
        Text("Update available", style = MaterialTheme.typography.titleSmall)
        Text("Watch app $name", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    status = when (val result = WatchUpdater.install(context, url) { status = it }) {
                        WatchUpdater.Result.Started -> "Confirm the install on the next screen"
                        WatchUpdater.Result.NeedsPermission ->
                            if (WatchUpdater.openPermission(context)) {
                                "Allow SpenDroid to install apps, then come back and tap Install"
                            } else {
                                "This watch won't let apps install updates. Use Settings → Watch on your phone."
                            }
                        is WatchUpdater.Result.Failed -> result.message
                    }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Color.Black),
        ) { Text("Install", maxLines = 1) }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}
