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
        if (payday && days != null && daily != null) {
            say(
                Kind.PAYDAY, 100,
                "Payday! ${money(available)} to see you to $untilWhen - about ${money(daily)} a day, bills already allowed for.",
                "A fresh start: ${money(available)} until $untilWhen. That's about ${money(daily)} a day, with the bills already set aside.",
            )
        }
        if (!today.isAfter(budget.cycleStart.plusDays(1))) {
            val bills = budget.upcomingFixed
            bills.maxByOrNull { it.amountMinor }?.let { biggest ->
                val total = bills.sumOf { it.amountMinor }
                say(
                    Kind.BILLS_AHEAD, 72,
                    "${money(total)} of bills come out this cycle. The biggest is ${name(biggest.rule.payee)} ${onDay(biggest.dueDate, today)}.",
                    "This cycle's bills come to ${money(total)}, all allowed for. ${name(biggest.rule.payee)} is the biggest, ${onDay(biggest.dueDate, today)}.",
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
                    "${spelt(days).replaceFirstChar(Char::titlecase)} days to go and ${money(available)} to cover them - about ${money(daily ?: 0L)} a day.",
                    "${money(available)} for the last ${spelt(days)} days - that's about ${money(daily ?: 0L)} a day.",
                )
            }
        }

        // ---- where the pace stands ----
        val ahead = TodaysRead.aheadMinor(budget)
        when {
            payday -> Unit
            available <= 0L -> say(
                Kind.PACE, 95,
                "Nothing left to spend before payday" + (budget.nextIncomeDate?.let { " on ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)}" } ?: "") + ". Anything from here comes out of next cycle.",
            )
            pace == BudgetPace.Pace.OVER -> say(
                Kind.PACE, 85,
                (ahead?.let { "${money(-it)} over the days so far." } ?: "Over the days so far.") + (daily?.let { " About ${money(it)} a day from here gets you to payday." } ?: ""),
                (ahead?.let { "${money(-it)} more spent than the days so far allow." } ?: "More spent than the days so far allow.") + (daily?.let { " Keeping to about ${money(it)} a day gets you to payday." } ?: ""),
            )
            pace == BudgetPace.Pace.TIGHT -> say(
                Kind.PACE, 80,
                "Just behind the days." + (daily?.let { " About ${money(it)} a day keeps you on track." } ?: ""),
                "A little quicker than the days so far." + (daily?.let { " ${money(it)} a day brings it back." } ?: ""),
            )
            ahead != null && ahead >= 500L -> say(
                Kind.PACE, 50,
                "${money(ahead)} ahead of the days. A normal day keeps you there.",
                "You're ${money(ahead)} ahead of the days.",
                "Comfortably on pace - ${money(ahead)} ahead of the days.",
            )
            else -> say(Kind.PACE, 45, "Right on pace." + (daily?.let { " ${money(it)} a day gets you to payday." } ?: ""))
        }

        // ---- today ----
        if (todays != null && todays > 0L && !payday) {
            // Today's total as Home's "Spent today" has it, cards included; what's left of today's
            // amount is from what counts against it - card spending is met by the card's bill.
            val onCards = budget.spentTodayOnCardsMinor
            val spent = budget.spentTodayFromAccountsMinor + onCards
            val left = todays - budget.spentToday
            val cardNote = if (onCards > 0L && spent > onCards) " (${money(onCards)} of it on cards)" else if (onCards > 0L) " (on cards)" else ""
            val shops = budget.cycleSpends
                .filter { RecurringAnalyzer.parseBookingDate(it.bookingDate) == today }
                .groupBy { name(it.payee) }
                .mapValues { (_, rows) -> rows.sumOf { -it.amountMinor } }
                .entries.sortedByDescending { it.value }.map { it.key }
            val where = when (shops.size) {
                0 -> ""
                1 -> ", all at ${shops[0]}"
                2 -> ", at ${shops[0]} and ${shops[1]}"
                else -> ", mostly at ${shops[0]}"
            }
            when {
                spent == 0L && hour in 5..11 -> say(
                    Kind.TODAY, 78,
                    "Morning. Nothing spent yet - today's ${money(todays)} is all yours.",
                    "Nothing spent yet today. You've ${money(todays)} for it.",
                )
                spent == 0L -> say(
                    Kind.TODAY, 70,
                    "Nothing spent today so far - your ${money(todays)} is untouched.",
                    "A quiet day so far: ${money(todays)} still to spend today.",
                )
                hour >= 18 && left >= 0L -> say(
                    Kind.TODAY, 80,
                    "Today came in at ${money(spent)}$cardNote. ${money(left)} of today's ${money(todays)} rolls into tomorrow.",
                    "${money(spent)} today$cardNote - ${money(left)} of your ${money(todays)} left, and that goes into the days ahead.",
                )
                hour >= 18 -> say(
                    Kind.TODAY, 82,
                    "Today came in at ${money(spent)}, ${money(-left)} over your ${money(todays)}. The days ahead share it out.",
                    "${money(spent)} today, a bit over your ${money(todays)}. Tomorrow's amount takes the difference.",
                )
                left >= 0L -> say(
                    Kind.TODAY, 76,
                    "${money(spent)} so far today$where$cardNote. ${money(left)} of today's is left.",
                    "So far today: ${money(spent)}$where$cardNote, with ${money(left)} still to spend.",
                )
                else -> say(
                    Kind.TODAY, 83,
                    "${money(spent)} so far today$where$cardNote - ${money(-left)} more than today's ${money(todays)}. The days ahead share it out.",
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
                        "${money(amount)} at $shop - your biggest spend this cycle." + (daily?.let { " Your daily amount is now ${money(it)}." } ?: ""),
                        "$shop, ${money(amount)}: the biggest spend this cycle." + (ahead?.let { if (it + amount > 0L && it < 0L) " Without it, you'd be ${money(it + amount)} ahead of the days." else "" } ?: ""),
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
                heading?.let { "Your ${c.label} is heading for about ${money(it)}, more than $whose." } ?: "Your ${c.label} is already past $whose, at ${money(r.spentMinor)}.",
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
            val before = budget.lastSevenDaysMinor.dropLast(1).reversed()
            val streak = before.takeWhile { it <= daily }.size + if (hour >= 18 && budget.spentToday <= (todays ?: 0L)) 1 else 0
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
                    target != today -> " Tomorrow's one."
                    todays != null && todays >= usual -> " Today's allowance covers that."
                    todays != null -> " A little more than today's ${money(todays)}."
                    else -> ""
                }
                say(Kind.WEEKDAY, 58, "$plural usually cost you about ${roughly((usual))}.$tail")
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
        moves.filter { it.second < 0 }.minByOrNull { it.second }?.let { (t, diff) ->
            if (bad) {
                say(Kind.BRIGHT_SIDE, 75, "${t.category.label} has been quieter - ${money(-diff)} under usual. That's helping.")
            } else {
                say(Kind.CATEGORY, 55, "${t.category.label} is ${money(-diff)} under usual this cycle.", "${money(-diff)} less on ${t.category.label.lowercase()} than usual this cycle.")
            }
        }
        if (!bad) {
            moves.filter { it.second > 0 }.maxByOrNull { it.second }?.let { (t, diff) ->
                say(Kind.CATEGORY, 54, "${t.category.label} is ${money(diff)} over usual this cycle - easily covered while you're ahead.")
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

    private fun name(payee: String): String = ShopName.clean(payee) ?: payee.trim()

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
