package com.spendroid.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
                        onSeeRecent = { nav.navigate("recent") },
                        onSeeUpcoming = { nav.navigate("upcoming") },
                        onOpenPhone = onOpenPhone,
                    )
                }
                composable("recent") { RecentList(reading) { index -> nav.navigate("pick/$index") } }
                composable("upcoming") { UpcomingList(reading) }
                composable("roundup") { shownRoundup?.let { RoundupScreen(it) } ?: nav.popBackStack() }
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
    val state = rememberPagerState(pageCount = { pages.size })
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
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { if (onOpenPhone()) openedOnPhone = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Open on phone",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onPrimary,
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

@Composable
private fun Hero(r: BudgetReading) {
    // Three parts in proportion - spent, behind even pace, left - which no stock indicator draws,
    // so it is drawn here, in the Material stroke and gaps.
    val segments = BudgetArc.drawOrder(BudgetArc.segments(r.budgetLeft, r.cycleLeft))
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 8.dp.toPx()
            var at = ARC_START
            segments.forEach { s ->
                val sweep = ARC_SWEEP * s.weight
                val colour = when (s.kind) {
                    BudgetArc.Kind.SPENT -> Blue
                    BudgetArc.Kind.BEHIND -> Red
                    BudgetArc.Kind.LEFT -> Green
                }
                arc(at + 2f, sweep - 4f, colour, w)
                at += sweep
            }
        }
        Centre {
            Text("Available to spend", style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(r.text("availableFull"), style = MaterialTheme.typography.numeralMedium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            val badge = when (r.pace) { "OVER" -> "▲ Over"; "TIGHT" -> "◆ Tight"; else -> "● On track" }
            Badge(badge, paceColour(r.pace))
            Spacer(Modifier.height(6.dp))
            Text(r.text("daysLine"), style = MaterialTheme.typography.bodyMedium)
            Text(r.text("incomeLine"), style = MaterialTheme.typography.bodySmall, color = Muted)
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

@Composable
private fun Today(r: BudgetReading) = Centre {
    Text("Today", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Line("From accounts", r.text("todayAccounts"))
    Line("On credit cards", r.text("todayCards"), r.text("todayCardsPending"))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Track))
    Spacer(Modifier.height(4.dp))
    Line("This cycle", r.text("thisCycle"))
    r.text("inAccount").takeIf { it.isNotEmpty() }?.let { Line("In the account", it) }
}

@Composable
private fun Categories(r: BudgetReading) = Centre {
    Text("This cycle by category", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    r.categories.take(4).forEach { c ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Color(c.colour), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(c.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (c.limit.isNotEmpty()) "${c.spent} of ${c.limit}" else c.spent,
                style = MaterialTheme.typography.labelSmall,
                color = if (c.over) Red else Muted,
            )
        }
        c.share?.let { share ->
            Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp).background(Track, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier.fillMaxWidth(share.coerceAtLeast(0.02f)).fillMaxHeight()
                        .background(if (c.over) Red else Color(c.colour), RoundedCornerShape(2.dp)),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
    }
}

/** The Recent page: the latest payment in full, and a way into the whole list. */
@Composable
private fun RecentSummary(r: BudgetReading, onSeeAll: () -> Unit) = Centre {
    Text("Recent", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    r.recent.firstOrNull()?.let { t ->
        Text(t.payee, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(t.amount, style = MaterialTheme.typography.numeralExtraSmall, maxLines = 1)
        Text(
            t.category + if (t.pending) " · pending" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (t.pending) Pending else Muted,
            maxLines = 1,
        )
    }
    Spacer(Modifier.height(8.dp))
    SeeAll("See all ${r.recent.size}", onSeeAll)
}

@Composable
private fun SeeAll(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White),
    ) { Text(label, maxLines = 1) }
}

/** Every recent payment, scrolled with the crown; tap one to change its category. */
@Composable
private fun RecentList(r: BudgetReading, onPick: (Int) -> Unit) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Recent") } }
            items(r.recent.size) { index ->
                val t = r.recent[index]
                Card(
                    onClick = { onPick(index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(t.colour), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.payee,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(t.amount, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        t.category + if (t.pending) " · pending" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (t.pending) Pending else Muted,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            item {
                Text(
                    "Tap one to change its category",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
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

@Composable
private fun Week(r: BudgetReading) {
    val values = r.week
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val end = runCatching { LocalDate.parse(r.text("weekEnds")) }.getOrNull()
    Centre {
        Text("Last 7 days", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEachIndexed { i, v ->
                Box(
                    Modifier.width(12.dp)
                        .fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.05f))
                        .background(if (i == values.lastIndex) Green else Blue, RoundedCornerShape(6.dp)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            values.indices.forEach { i ->
                val day = end?.minusDays((values.size - 1 - i).toLong())?.dayOfWeek?.name?.take(1).orEmpty()
                Text(
                    day,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == values.lastIndex) Color.White else Muted,
                    fontWeight = if (i == values.lastIndex) FontWeight.Bold else null,
                    modifier = Modifier.width(12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(r.text("updated"), style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
private fun CardScreen(c: BudgetReading.Card) {
    val tone = if (c.over) Red else Green
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            progress = { c.capShare ?: 0f },
            modifier = Modifier.fillMaxSize().padding(4.dp),
            startAngle = ARC_START,
            endAngle = ARC_START + ARC_SWEEP,
            colors = ProgressIndicatorDefaults.colors(indicatorColor = tone, trackColor = Track),
        )
        c.statementGone?.let { gone ->
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                val angle = Math.toRadians((ARC_START + ARC_SWEEP * gone).toDouble())
                val radius = size.width / 2 - w / 2 - 8.dp.toPx()
                val centre = Offset(size.width / 2 + radius * cos(angle).toFloat(), size.height / 2 + radius * sin(angle).toFloat())
                drawCircle(Color.Black, radius = w * 0.9f, center = centre)
                drawCircle(Color.White, radius = w * 0.65f, center = centre)
            }
        }
        Centre {
            Text(c.text("name"), style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.text("since"), style = MaterialTheme.typography.numeralSmall, maxLines = 1)
            // "since statement" and the pending part on one line, where they were two.
            val pending = c.text("pending").removePrefix("incl. ")
            Text(
                "since statement" + if (pending.isNotEmpty()) " · $pending" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (pending.isNotEmpty()) Pending else Muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Badge(if (c.over) "▲ Heading over" else "● On track", tone)
            Spacer(Modifier.height(4.dp))
            // "~£802.71 of usual £959.76": the projection against its measure, on one line.
            val heading = c.text("pace").removePrefix("On pace for ")
            val against = c.text("cap").replace("usual bill ", "usual ")
            val paceLine = listOf(heading, against).filter { it.isNotEmpty() }.joinToString(" of ")
            if (paceLine.isNotEmpty()) {
                Text(paceLine, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
            }
            Text(
                c.text("billed").replace("Billed ", "Bill ").replace(" · due ", " due "),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The To come page: the total still to leave before payday, the next one, and the whole list. */
@Composable
private fun UpcomingSummary(r: BudgetReading, onSeeAll: () -> Unit) = Centre {
    Text("To come before payday", style = MaterialTheme.typography.titleSmall, maxLines = 1)
    Spacer(Modifier.height(6.dp))
    val items = r.upcoming
    if (items.isEmpty()) {
        Text("Nothing else before payday", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center)
        return@Centre
    }
    Text(r.text("upcomingTotal"), style = MaterialTheme.typography.numeralSmall, maxLines = 1)
    Text(
        "${items.size} payment${if (items.size == 1) "" else "s"}",
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
    Spacer(Modifier.height(4.dp))
    items.first().let { next ->
        Text("Next: ${next.name}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("${next.amount} · ${next.date}", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
    Spacer(Modifier.height(8.dp))
    SeeAll("See all", onSeeAll)
}

/** Everything to come before payday, scrolled with the crown. */
@Composable
private fun UpcomingList(r: BudgetReading) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("To come before payday", maxLines = 2, textAlign = TextAlign.Center) } }
            items(r.upcoming) { u ->
                // Nothing to do with one yet; full colour, rather than a disabled card's dimmed one.
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            u.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(u.amount, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(u.date, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            item {
                Text(
                    "${r.text("upcomingTotal")} in all",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The day's roundup, as the phone worded it: the headline in the pace's colour, then the rest. */
@Composable
private fun RoundupScreen(r: Roundup) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Roundup", maxLines = 1) } }
            item {
                Text(
                    r.headline,
                    style = MaterialTheme.typography.titleLarge,
                    color = paceColour(r.pace),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(r.lines) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
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
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
        ) { Text("Install", maxLines = 1) }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}
