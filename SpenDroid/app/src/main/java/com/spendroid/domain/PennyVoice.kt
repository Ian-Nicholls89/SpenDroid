package com.spendroid.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What Penny could say on Home, most relevant first: today's spending and what's left of it,
 * a streak, what comes out next, the countdown to payday, a category against its usual, this
 * cycle against the last, a weekday's habit, a big one-off, a card heading over its usual bill,
 * halfway, where the cycle is likely to end. Every figure from the budget; no line is said
 * that the figures don't bear out.
 *
 * Ranked by the moment: when it's tight, the pace and what helps lead; ahead, the good news and
 * the curious; near payday, the countdown. Each kind has a few wordings, turned over by the day,
 * and the top few take turns as the lead from one visit to the next - [lead] - so she doesn't
 * say the same thing every time the app opens. The rest are a tap away.
 *
 * Never a telling-off: over is said plainly, with what gets you to payday, and alongside it
 * whatever's going well.
 */
object PennyVoice {

    enum class Kind { PAYDAY, BILLS_AHEAD, COUNTDOWN, ONE_OFF, PACE, TODAY, BRIGHT_SIDE, COMING_UP, FORECAST, CARD, MILESTONE, STREAK, WEEKDAY, CATEGORY, LAST_CYCLE }

    data class Line(val kind: Kind, val text: String, val rank: Int)

    /** A card's period row, for "heading over its usual bill". */
    data class Card(val label: String, val row: PeriodRows.Row)

    /** How many of the top lines take turns as the lead, visit by visit. */
    private const val TURNS = 3

    /** The line to lead with on the [visit]th time Home is opened: the top few in turn. */
    fun lead(lines: List<Line>, visit: Int): Int = if (lines.isEmpty()) 0 else Math.floorMod(visit, minOf(TURNS, lines.size))

    /** The same, by how many lines there are: for lines that aren't [Line]s - the tabs'. */
    fun lead(count: Int, visit: Int): Int = if (count == 0) 0 else Math.floorMod(visit, minOf(TURNS, count))

    fun lines(
        budget: BudgetSnapshot,
        now: LocalDateTime,
        money: (Long) -> String,
        /** Category trends by pay cycle, as Insights has them. */
        trends: List<CategoryTrend> = emptyList(),
        cards: List<Card> = emptyList(),
    ): List<Line> {
        val today = budget.asOf
        val day = today.toEpochDay()
        fun roughly(minor: Long) = money(round(minor)).removeSuffix(".00")
        // Shops by name alone, the branch left off: places as the user's own payees show them.
        val places = ShopName.placesIn(budget.cycleSpends.map { it.payee })
        fun name(payee: String): String = ShopName.clean(payee, places) ?: payee.trim()
        val out = mutableListOf<Line>()
        fun say(kind: Kind, rank: Int, vararg wordings: String) {
            if (wordings.isEmpty()) return
            out += Line(kind, wordings[Math.floorMod(day + kind.ordinal, wordings.size.toLong()).toInt()], rank)
        }

        val days = budget.daysUntilNextIncome?.takeIf { it > 0 }
        val available = budget.availableToSpend
        val pace = BudgetPace.of(budget)
        val payday = budget.asOf == budget.cycleStart
        val bad = available <= 0L || pace != BudgetPace.Pace.ON_TRACK
        // A day's share now, and today's as it stood this morning: before what today's spent.
        val daily = days?.let { maxOf(0L, available) / it }
        val todays = days?.let { maxOf(0L, available + budget.spentToday) / it }
        val hour = now.hour
        val untilWhen = budget.nextIncomeDate?.format(DAY_MONTH)

        // ---- payday ----
        if (payday && days != null && available <= 0L) {
            // Paid, and the bills to come take all of it: said plainly, not "£0.00 a day".
            say(
                Kind.PAYDAY, 100,
                "Payday - but the bills before $untilWhen take all of it.",
            )
        } else if (payday && days != null && daily != null) {
            say(
                Kind.PAYDAY, 100,
                "Payday! ${money(available)} to $untilWhen - about ${money(daily)} a day.",
                "A fresh start: ${money(available)} until $untilWhen, about ${money(daily)} a day.",
            )
        }
        if (!today.isAfter(budget.cycleStart.plusDays(1))) {
            val bills = budget.upcomingFixed
            bills.maxByOrNull { it.amountMinor }?.let { biggest ->
                val total = bills.sumOf { it.amountMinor }
                say(
                    Kind.BILLS_AHEAD, 72,
                    "${money(total)} of bills this cycle. Biggest: ${name(biggest.rule.payee)}, ${onDay(biggest.dueDate, today).removePrefix("on ")}.",
                    "This cycle's bills come to ${money(total)}, all allowed for.",
                )
            }
        }

        // ---- the countdown ----
        if (!payday && days != null && days <= 3 && available > 0L) {
            if (days == 1) {
                say(Kind.COUNTDOWN, 90, "Payday's tomorrow, and ${money(available)} covers today.", "One day to go - ${money(available)} for today, and then it's payday.")
            } else {
                say(
                    Kind.COUNTDOWN, 90,
                    "${spelt(days).replaceFirstChar(Char::titlecase)} days to go, ${money(available)} left - about ${money(daily ?: 0L)} a day.",
                    "${money(available)} for the last ${spelt(days)} days - about ${money(daily ?: 0L)} a day.",
                )
            }
        }

        // ---- where the pace stands ----
        val ahead = TodaysRead.aheadMinor(budget)
        when {
            payday -> Unit
            available <= 0L -> say(
                Kind.PACE, 95,
                "Nothing left until payday" + (budget.nextIncomeDate?.let { ", " + paydayOn(it, today).removePrefix("on ") } ?: "") + " - spend now is next cycle's.",
            )
            pace == BudgetPace.Pace.OVER -> say(
                Kind.PACE, 85,
                (ahead?.let { "${money(-it)} over the days so far." } ?: "Over the days so far.") + (daily?.let { " ${money(it)} a day gets you to payday." } ?: ""),
                (ahead?.let { "${money(-it)} more than the days allow." } ?: "More than the days allow.") + (daily?.let { " ${money(it)} a day gets you to payday." } ?: ""),
            )
            pace == BudgetPace.Pace.TIGHT -> say(
                Kind.PACE, 80,
                "Just behind the days." + (daily?.let { " About ${money(it)} a day keeps you on track." } ?: ""),
                "A little quick for the days." + (daily?.let { " ${money(it)} a day brings it back." } ?: ""),
            )
            ahead != null && ahead >= 500L -> say(
                Kind.PACE, 50,
                "${money(ahead)} ahead of the days. A normal day keeps you there.",
                "You're ${money(ahead)} ahead of the days.",
                "Comfortably on pace - ${money(ahead)} ahead of the days.",
            )
            else -> say(Kind.PACE, 45, "Right on pace." + (daily?.let { " ${money(it)} a day gets you to payday." } ?: ""))
        }

        // Today's amount as a figure only when it's the one on Home's "£X a day": once today's
        // spending has moved that on, two different daily figures side by side read as a mistake.
        val todaysAmount = if (todays != null && daily != null && todays != daily) "today's amount" else "today's ${todays?.let(money)}"

        // ---- today ----
        if (todays != null && todays > 0L && !payday) {
            // Like with like: what counts against today's amount is from the accounts - card
            // spending is met by the card's bill. Home's "Spent today", cards and all, is just
            // below, with the shops in Spending: she says only how today stands.
            val onCards = budget.spentTodayOnCardsMinor
            val counted = budget.spentToday
            val left = todays - counted
            // "£17.64 from your account" when cards are in Home's figure too, so the two don't clash.
            val spentWord = if (onCards > 0L) "${money(counted)} from your account" else "${money(counted)} spent"
            // The day before payday what's left isn't tomorrow's, it's the new cycle's.
            val intoNext = if (budget.daysUntilNextIncome == 1) "the new cycle" else "tomorrow"
            when {
                // Nothing left before payday: there's no amount for today to be within or over.
                available <= 0L -> if (counted > 0L) say(
                    Kind.TODAY, 83,
                    "$spentWord today - that's next cycle's money.",
                )
                counted == 0L && onCards > 0L -> say(
                    Kind.TODAY, if (hour >= 18) 80 else 72,
                    "Only cards today, so all of $todaysAmount is still yours.",
                )
                counted == 0L && hour in 5..11 -> say(
                    Kind.TODAY, 78,
                    "Morning. Nothing spent yet - $todaysAmount is all yours.",
                    "Nothing spent yet today. You've ${money(todays)} for it.",
                )
                counted == 0L -> say(
                    Kind.TODAY, 70,
                    "Nothing spent today so far - your ${money(todays)} is untouched.",
                    "A quiet day so far: ${money(todays)} still to spend today.",
                )
                left == 0L -> say(
                    Kind.TODAY, if (hour >= 18) 80 else 76,
                    "${todaysAmount.replaceFirstChar(Char::titlecase)}, used to the penny.",
                )
                hour >= 18 && left > 0L -> say(
                    Kind.TODAY, 80,
                    "${money(left)} of $todaysAmount left - it rolls into $intoNext.",
                    "Under $todaysAmount by ${money(left)}. That goes into $intoNext.",
                )
                left > 0L -> say(
                    Kind.TODAY, 76,
                    "$spentWord today - ${money(left)} of $todaysAmount left.",
                    "${money(left)} of $todaysAmount still to spend.",
                )
                // Over: "a bit" only when it is, up to a quarter over.
                -left <= todays / 4 -> say(
                    Kind.TODAY, if (hour >= 18) 82 else 83,
                    "A bit over $todaysAmount - tomorrow's amount takes it.",
                )
                else -> say(
                    Kind.TODAY, if (hour >= 18) 82 else 83,
                    "${money(-left)} over $todaysAmount - the days ahead share it out.",
                )
            }
        }

        // ---- a big one-off, today or yesterday ----
        val amounts = budget.cycleSpends.map { -it.amountMinor }.sorted()
        if (amounts.size >= 5) {
            val typical = amounts[amounts.size / 2]
            budget.cycleSpends.maxByOrNull { -it.amountMinor }?.let { big ->
                val date = RecurringAnalyzer.parseBookingDate(big.bookingDate)
                val amount = -big.amountMinor
                if (date != null && !date.isBefore(today.minusDays(1)) && amount >= maxOf(5_000L, typical * 3)) {
                    val shop = name(big.payee)
                    say(
                        Kind.ONE_OFF, 88,
                        "${money(amount)} at $shop - your biggest spend this cycle.",
                        "$shop, ${money(amount)}: the biggest this cycle.",
                    )
                }
            }
        }

        // ---- what comes out next ----
        val soon = budget.upcomingFixed.filter { !it.dueDate.isBefore(today) && !it.dueDate.isAfter(today.plusDays(2)) }.minByOrNull { it.dueDate }
        if (soon != null) {
            val who = name(soon.rule.payee)
            say(
                Kind.COMING_UP, 70,
                "$who takes ${money(soon.amountMinor)} ${whenDue(soon.dueDate, today)}. It's already allowed for.",
                "${money(soon.amountMinor)} to $who goes out ${whenDue(soon.dueDate, today)} - already set aside.",
            )
        } else if (!payday && days != null && days <= 3 && budget.upcomingFixed.none { it.dueDate.isBefore(budget.nextIncomeDate ?: today) }) {
            say(Kind.COMING_UP, 64, "Nothing else comes out before payday.", "No more bills before payday.")
        }

        // ---- where it's likely to end ----
        if (!payday && days != null && days <= 5 && available > 0L) {
            val recent = budget.lastSevenDaysMinor.dropLast(1)
            if (recent.isNotEmpty()) {
                val usual = recent.sum() / recent.size
                val left = available - usual * days
                if (left >= 500L) {
                    say(Kind.FORECAST, 66, "You're set to finish around ${roughly((left))} to the good.", "Spending as you have this week, you'd finish about ${roughly((left))} to the good.")
                }
            }
        }

        // ---- cards ----
        cards.firstOrNull { c ->
            val r = c.row
            r.kind == PeriodRows.Kind.STATEMENT && r.againstMinor != null && r.pace != BudgetPace.Pace.ON_TRACK &&
                (r.projectedMinor ?: r.spentMinor) > r.againstMinor
        }?.let { c ->
            val r = c.row
            val against = r.againstMinor!!
            val whose = if (r.against == PeriodRows.Against.LIMIT) "your ${money(against)} limit" else "its usual ${money(against)} bill"
            val heading = r.projectedMinor?.takeIf { it > r.spentMinor }
            say(
                Kind.CARD, 65,
                heading?.let { "${c.label} is heading for ${money(it)}, over $whose." } ?: "${c.label} is at ${money(r.spentMinor)}, past $whose.",
            )
        }

        // ---- halfway ----
        val used = BudgetPace.usedFraction(budget)
        val elapsed = BudgetPace.elapsedFraction(budget)
        if (used != null && elapsed != null && elapsed in 0.47f..0.56f && used < 1f) {
            val left = ((1f - used) * 100).toInt()
            say(Kind.MILESTONE, 62, "Halfway through the cycle, with $left% still left.", "Half the cycle gone, and $left% of the money still to spend.")
        }

        // ---- a streak under the daily amount ----
        if (daily != null && daily > 0L) {
            // Back through this cycle, not just the week: the last seven days stopped it at seven.
            val before = generateSequence(today.minusDays(1)) { it.minusDays(1) }
                .takeWhile { !it.isBefore(budget.cycleStart) }
                .map { budget.spendByDay[it] ?: 0L }
            val streak = before.takeWhile { it <= daily }.count() + if (hour >= 18 && budget.spentToday <= (todays ?: 0L)) 1 else 0
            if (streak >= 3) {
                say(
                    Kind.STREAK, 60,
                    "That's ${spelt(streak)} days in a row under your daily amount.",
                    "${spelt(streak).replaceFirstChar(Char::titlecase)} days running under your daily amount - nicely done.",
                )
            }
        }

        // ---- a weekday's habit ----
        val target = if (hour >= 18) today.plusDays(1) else today
        weekdayUsual(budget.spendByDay, target.dayOfWeek, today)?.let { (usual, overall) ->
            if (usual >= 1_500L && usual >= overall * 3 / 2) {
                val plural = target.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK) + "s"
                val tail = when {
                    target != today -> " - tomorrow's one."
                    todays != null && todays >= usual -> " - today's amount covers it."
                    // "A little more" only when it is; with nothing to spend today, nothing to compare.
                    todays != null && todays > 0L && usual <= todays * 3 / 2 -> ", a little over $todaysAmount."
                    todays != null && todays > 0L && available > 0L -> ", more than $todaysAmount."
                    else -> "."
                }
                say(Kind.WEEKDAY, 58, "$plural usually cost about ${roughly((usual))}$tail")
            }
        }

        // ---- categories against their usual, and the cycle against the last ----
        val byCycle = trends.filter { it.basis == TrendBasis.PAY_CYCLE && it.seriesMinor.size >= 3 }
        val moves = byCycle.mapNotNull { t ->
            val before = t.seriesMinor.dropLast(1).sorted()
            val usual = before[before.size / 2]
            if (usual < 1_000L) return@mapNotNull null
            val diff = t.lastMinor - usual
            if (kotlin.math.abs(diff) < 500L) null else t to diff
        }
        // "Under usual" only in the last days before payday: earlier, a cycle part-spent is under any
        // whole one - and a category of regular bills most of all, its bills not yet out (26 Oct
        // 2026: savings "under usual" on day 4, its £250 due that morning).
        val cycleNearlyDone = budget.daysUntilNextIncome?.let { it <= 3 } == true
        moves.filter { it.second < 0 && cycleNearlyDone }.minByOrNull { it.second }?.let { (t, diff) ->
            if (bad) {
                say(Kind.BRIGHT_SIDE, 75, "${t.category.label} has been quieter - ${money(-diff)} under usual. That's helping.")
            } else {
                say(Kind.CATEGORY, 55, "${t.category.label} is ${money(-diff)} under usual this cycle.", "${money(-diff)} less on ${t.category.label.lowercase()} than usual this cycle.")
            }
        }
        if (!bad) {
            moves.filter { it.second > 0 }.maxByOrNull { it.second }?.let { (t, diff) ->
                say(Kind.CATEGORY, 54, "${t.category.label} is ${money(diff)} over usual, but you're still ahead.")
            }
        }
        if (byCycle.isNotEmpty()) {
            val now = byCycle.sumOf { it.lastMinor }
            val last = byCycle.sumOf { it.seriesMinor[it.seriesMinor.size - 2] }
            val diff = last - now
            if (kotlin.math.abs(diff) >= 2_000L) {
                if (diff > 0) {
                    say(Kind.LAST_CYCLE, if (bad) 74 else 52, "You've spent ${roughly((diff))} less than at this point last cycle.")
                } else if (!bad) {
                    say(Kind.LAST_CYCLE, 51, "${roughly((-diff))} more than at this point last cycle - and still ahead of the days.")
                }
            }
        }

        return out.sortedByDescending { it.rank }
    }

    /** The usual spend on [weekday] over the last eight weeks, and on an average day then; null without enough history. */
    private fun weekdayUsual(byDay: Map<LocalDate, Long>, weekday: DayOfWeek, today: LocalDate): Pair<Long, Long>? {
        val from = today.minusWeeks(8)
        val window = generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.toList()
        if (byDay.keys.none { !it.isAfter(from) }) return null
        val those = window.filter { it.dayOfWeek == weekday }.map { byDay[it] ?: 0L }
        if (those.size < 4) return null
        val overall = window.sumOf { byDay[it] ?: 0L } / window.size
        return those.sum() / those.size to overall
    }

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** Payday: "on Wednesday" within the week; further off, the date - "on Wednesday" read as this one. */
    internal fun paydayOn(date: LocalDate, today: LocalDate): String =
        if (!date.isAfter(today.plusDays(6))) "on ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)}"
        else "on ${date.format(WEEKDAY_DAY_MONTH)}"

    private fun whenDue(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "on " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
    }

    private fun onDay(date: LocalDate, today: LocalDate): String =
        if (ChronoUnit.DAYS.between(today, date) <= 1) whenDue(date, today) else "on the ${date.dayOfMonth}${suffix(date.dayOfMonth)}"

    private fun suffix(d: Int) = when {
        d % 100 in 11..13 -> "th"
        d % 10 == 1 -> "st"
        d % 10 == 2 -> "nd"
        d % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun spelt(n: Int): String = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").getOrNull(n) ?: n.toString()

    /** A rough figure to the pound: an estimate shouldn't pretend to pennies. */
    private fun round(minor: Long): Long = (minor + 50) / 100 * 100
}
