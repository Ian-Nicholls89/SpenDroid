package com.spendroid.ui

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.PennyVoice
import com.spendroid.domain.RecurringAnalyzer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Everything Penny says on Home, every day of a pay cycle - morning, lunchtime and evening - for a
 * made-up household shaped like the user's: paid on the 25th (the Friday before, at a weekend),
 * bills through the month with savings on payday, a joint account, a credit card, and everyday
 * spending with a quiet patch and a dear weekend. Each line checked against the figures it's
 * about; all of them written to build/penny-cycle.txt to be read through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PennyCycleTest {

    private val personal = "acc-personal"
    private val card = "acc-card"
    private var seq = 0

    private fun tx(account: String, date: LocalDate, minor: Long, payee: String) = TransactionEntity(
        accountId = account, transactionId = "c${seq++}", bookingDate = date.toString(), valueDate = null,
        amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )

    /** The 25th, or the Friday before it at a weekend - as salaries are paid. */
    private fun payday(year: Int, month: Int): LocalDate {
        var d = LocalDate.of(year, month, 25)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** A direct debit: on its day, or the Monday after at a weekend. */
    private fun debit(year: Int, month: Int, day: Int): LocalDate {
        var d = LocalDate.of(year, month, day)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    private val bills = listOf(
        Triple("TUMBLE CLUB", 6_125L, 1), Triple("SWIM SCHOOL", 5_813L, 3), Triple("GYM CO", 3_060L, 5),
        Triple("PHONE CO", 3_250L, 7), Triple("SOFTWARE CO", 1_699L, 8), Triple("CHARITY", 1_000L, 15),
        Triple("KIDS SAVINGS", 5_000L, 18), Triple("STREAMER", 1_499L, 18), Triple("HELP TO SAVE", 20_000L, 25),
        Triple("PREMIUM SAVER", 5_000L, 26), Triple("UNION", 1_686L, 28), Triple("JOINT ACCOUNT BILLS", 50_000L, 28),
    )

    /** Everything that happened, June to the end of November: a fixed sequence, the same each run. */
    private fun history(salary: Long): List<TransactionEntity> {
        seq = 0
        val out = mutableListOf<TransactionEntity>()
        val random = java.util.Random(26)
        for (month in 6..11) {
            out += tx(personal, payday(2026, month), salary, "EMPLOYER LTD")
            bills.forEach { (payee, minor, day) -> out += tx(personal, debit(2026, month, day), -minor, payee) }
        }
        var day = LocalDate.of(2026, 5, 24)
        while (!day.isAfter(LocalDate.of(2026, 11, 30))) {
            // A quiet patch early in November, a dear weekend after.
            val quiet = day in LocalDate.of(2026, 11, 2)..LocalDate.of(2026, 11, 6)
            val dear = day in LocalDate.of(2026, 11, 7)..LocalDate.of(2026, 11, 8)
            if (!quiet) {
                out += tx(personal, day, -(300L + random.nextInt(1_200)), listOf("COFFEE SHOP", "BAKERY", "CORNER SHOP")[random.nextInt(3)])
                if (random.nextInt(3) == 0) out += tx(personal, day, -(2_000L + random.nextInt(4_000)), "SUPERMARKET")
                if (random.nextInt(4) == 0) out += tx(card, day, -(1_000L + random.nextInt(3_000)), listOf("RESTAURANT", "TOY SHOP", "PETROL")[random.nextInt(3)])
            }
            if (dear) out += tx(personal, day, -9_500, "THEME PARK")
            day = day.plusDays(1)
        }
        // The card's statement closes on the 23rd and is paid in full on the 17th after.
        for (month in 6..11) {
            val close = LocalDate.of(2026, month - 1, 23)
            val opened = close.minusMonths(1)
            val statement = -out.filter { it.accountId == card && it.amountMinor < 0 }
                .filter { LocalDate.parse(it.bookingDate).let { d -> d.isAfter(opened) && !d.isAfter(close) } }
                .sumOf { it.amountMinor }
            if (statement <= 0L) continue
            val paid = debit(2026, month, 17)
            out += tx(personal, paid, -statement, "CARD CO PAYMENT")
            out += tx(card, paid, statement, "PAYMENT RECEIVED")
        }
        return out
    }

    private var history: List<TransactionEntity> = emptyList()

    private fun stateOn(today: LocalDate): RootUiState {
        val known = history.filter { !LocalDate.parse(it.bookingDate).isAfter(today) }
        // What's in each account by the end of the day, from 1 June.
        val balance = 40_000L + known.filter { it.accountId == personal }.sumOf { it.amountMinor }
        val owed = known.filter { it.accountId == card }.sumOf { it.amountMinor }
        val accounts = listOf(
            AccountEntity(personal, "Made-up Bank", "Personal Account", "GBP", balance, 0L, AccountType.PERSONAL),
            AccountEntity(card, "Made-up Card", "Shop Card", "GBP", owed, 0L, AccountType.CREDIT_CARD, statementDayOfMonth = 23, paymentDayOfMonth = 17),
        )
        // As a sync leaves them: transfers between the user's own accounts - the card's repayment - paired.
        val pairs = com.spendroid.data.SyncRules.detectInternalTransfers(com.spendroid.data.SyncRules.transferCandidates(known, emptySet()))
        val paired = pairs.flatMap { (a, b) -> listOf(a.transactionId, b.transactionId) }.toSet()
        val transactions = known.map { if (it.transactionId in paired) it.copy(isInternalTransfer = true) else it }.sortedByDescending { it.bookingDate }
        val rules = RecurringAnalyzer.analyze(transactions)
        val budget = BudgetEngine.snapshot(transactions, rules, accounts, today.atTime(12, 0), budgetModel = BudgetModel.ROLLOVER)
        return RootUiState(
            hasCredentials = true, showRecurringOnly = false, accounts = accounts, transactions = transactions,
            budget = budget, rules = rules, budgetModel = BudgetModel.ROLLOVER, categoryHistory = CategoryEngine.history(transactions),
        )
    }

    /** A household with room to spend: about £750 a month after the bills. */
    @Test
    fun `every day of a comfortable cycle, everything she says makes sense`() = cycle(salary = 260_000, name = "comfortable")

    /** A month as tight as the user's on 26 Oct: little left after the bills, and some days over. */
    @Test
    fun `every day of a tight cycle, everything she says makes sense`() = cycle(salary = 195_000, name = "tight")

    private fun cycle(salary: Long, name: String) {
        history = history(salary)
        val report = StringBuilder()
        val problems = mutableListOf<String>()
        var day = LocalDate.of(2026, 10, 23)
        while (!day.isAfter(LocalDate.of(2026, 11, 25))) {
            val state = stateOn(day)
            val b = state.budget!!
            report.append("\n=== ${day.dayOfWeek.toString().take(3)} $day · payday ${b.nextIncomeDate} (${b.daysUntilNextIncome}) · ")
                .append("left £%.2f · spent today £%.2f · to come £%.2f\n".format(b.availableToSpend / 100.0, b.spentToday / 100.0, b.upcomingFixed.sumOf { it.amountMinor } / 100.0))
            val seen = mutableSetOf<String>()
            for (time in listOf(LocalTime.of(8, 5), LocalTime.of(13, 5), LocalTime.of(20, 5))) {
                pennyLines(state, day.atTime(time)).forEach { line ->
                    if (seen.add(line.text)) report.append("  $time ${line.kind}: ${line.text}\n")
                    check(day, b, line)?.let { problems += "$day $time ${line.kind}: ${line.text}  <- $it" }
                }
            }
            // The month, cards and all (0.7.1): Home's figure plus this cycle's card bills.
            com.spendroid.domain.MonthlyAvailable.of(b, state.transactions, state.accounts)?.let { m ->
                val said = com.spendroid.domain.MonthlyAvailable.line(m) { com.spendroid.ui.formatMoney(it, "GBP") }
                report.append("  [Month] £%.2f = £%.2f left + %s · cards since payday £%.2f · %s\n".format(
                    m.totalMinor / 100.0, m.leftMinor / 100.0, m.bills.joinToString(" + ") { "${it.label} £%.2f${if (it.paid) " paid" else ""}".format(it.amountMinor / 100.0) },
                    m.spentOnCardsMinor / 100.0, said))
                if (said.length > LONGEST) problems += "$day [Month]: $said  <- too long"
                if (m.leftMinor != b.availableToSpend) problems += "$day [Month]: not Home's figure"
                if (m.bills.groupBy { it.label to it.due }.any { it.value.size > 1 }) problems += "$day [Month]: a bill counted twice"
                val cards = state.accounts.filter { it.accountType == com.spendroid.data.db.AccountType.CREDIT_CARD }.map { it.label.trim() }
                m.bills.filter { it.label !in cards }.forEach { problems += "$day [Month]: a bill named ${it.label}, not its card" }
            }
            // Each card's and account's own page on Home: her line there, held to the same length.
            com.spendroid.domain.PeriodRows.rows(state.accounts.filter { it.id != b.potAccountId }.map { it.id }, b, state.accounts, state.transactions).forEach { row ->
                if (row.kind == com.spendroid.domain.PeriodRows.Kind.BUDGET) return@forEach
                val said = com.spendroid.domain.TodaysRead.of(row) { com.spendroid.ui.formatMoney(it, "GBP") }
                report.append("  [${row.label.trim()}] $said\n")
                if (said.length > LONGEST) problems += "$day [${row.label.trim()}]: $said  <- too long: ${said.length} characters"
            }
            val home = com.spendroid.domain.TodaysRead.of(b) { com.spendroid.ui.formatMoney(it, "GBP") }
            if (home.length > LONGEST) problems += "$day [Home, quiet]: $home  <- too long"
            // The tabs (0.7.0): what she says on each, at midday.
            for (tab in listOf(com.spendroid.AppScreen.Spending, com.spendroid.AppScreen.Rules, com.spendroid.AppScreen.Accounts)) {
                tabLines(state, tab).forEach { line ->
                    report.append("  [${tab.shortTitle}] ${line.text}${line.target?.let { "  -> $it" } ?: ""}\n")
                    tabCheck(b, line)?.let { problems += "$day [${tab.shortTitle}]: ${line.text}  <- $it" }
                }
            }
            day = day.plusDays(1)
        }
        java.io.File("build/penny-cycle-$name.txt").writeText(report.toString())
        // The streak counted no further than a week; in a quiet month it grows.
        if (name == "comfortable") assertTrue("the streak grows", Regex("(1[0-9]|2[0-9]) days (in a row|running)").containsMatchIn(report))
        assertTrue(problems.joinToString("\n", prefix = "\n"), problems.isEmpty())
    }

    /** 0.7.1: one short sentence or two - the bubble on Home was a paragraph. */
    private val LONGEST = 70

    /** A tab's line: whole, its figures sound, and a link wherever it names something to see. */
    private fun tabCheck(b: com.spendroid.domain.BudgetSnapshot, line: com.spendroid.domain.TabVoice.Line): String? {
        val t = line.text
        if (Regex("null|NaN|£-|-£|\\$\\{| {2}|\\.\\.").containsMatchIn(t)) return "broken figure or text"
        if (!(t.endsWith(".") || t.endsWith("!"))) return "unfinished"
        if (t.length > LONGEST) return "too long: ${t.length} characters"
        if (Regex("on (Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b").containsMatchIn(t) && "ext big one" !in t && "closes" !in t && "goes" !in t) return "a weekday, for what"
        if (("went up" in t || "contract ends" in t || "due on payday" in t || "ext big one" in t) && line.target == null) return "names a bill, but no link to it"
        if ("due on payday" in t && b.upcomingFixed.any { it.rule.key == (line.target as? com.spendroid.domain.TabVoice.Target.Bill)?.ruleKey && !it.dueDate.isBefore(b.nextIncomeDate ?: it.dueDate) }) return "said to be next month's, but counted in this one"
        return null
    }

    /** What would make a line wrong, read against the figures it's about; null when it's fine. */
    private fun check(day: LocalDate, b: com.spendroid.domain.BudgetSnapshot, line: PennyVoice.Line): String? {
        val t = line.text
        if (Regex("null|NaN|£-|-£|\\$\\{| {2}|\\.\\.").containsMatchIn(t)) return "broken figure or text"
        if (!(t.endsWith(".") || t.endsWith("!") || t.endsWith("?"))) return "unfinished"
        if (t.length > LONGEST) return "too long: ${t.length} characters"
        val days = b.daysUntilNextIncome ?: return null
        val weekday = Regex("payday on (Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b").find(t)
        if (weekday != null && days > 6) return "payday named by weekday, $days days off"
        if (("under usual" in t || "quieter" in t) && days > 3) return "under usual, $days days before payday"
        if ("Nothing left" in t && b.availableToSpend > 0) return "nothing left, with £${b.availableToSpend / 100.0} left"
        Regex("(\\d+) days? (to|until|before) payday").find(t)?.let { m ->
            if (m.groupValues[1].toInt() != days) return "says ${m.groupValues[1]} days to payday; it's $days"
        }
        if (day == b.nextIncomeDate && line.kind == PennyVoice.Kind.COUNTDOWN) return "a countdown on payday itself"
        // 26 Oct 2026, read through: each of these was said, and none of them was true.
        if (b.availableToSpend <= 0L && Regex("today's £|days ahead share|£0\\.00 a day").containsMatchIn(t)) return "a daily amount, with nothing left"
        Regex("£([\\d,.]+) of (today's|your) £([\\d,.]+)").find(t)?.let { m -> if (m.groupValues[1] == m.groupValues[3]) return "\"£x of £x\", not \"all\"" }
        if ("£0.00 of" in t) return "£0.00 of something"
        if ("daily amount is now £0.00" in t) return "a daily amount of nothing"
        if ("Card Co Payment" in t) return "a card's bill, paid, told as spending"
        Regex("[Aa] bit over today's £([\\d,.]+)").find(t)?.let { m ->
            val of = m.groupValues[1].replace(",", "").toDouble()
            if (b.spentToday / 100.0 > of * 1.25 + 0.01) return "\"a bit over\" when well over"
        }
        Regex("cost about £([\\d,]+), a little over today's £([\\d,.]+)").find(t)?.let { m ->
            if (m.groupValues[1].replace(",", "").toDouble() > m.groupValues[2].replace(",", "").toDouble() * 1.5) return "\"a little more\" when far more"
        }
        if (b.daysUntilNextIncome == 1 && "rolls into tomorrow" in t) return "tomorrow is payday"
        return null
    }

    /**
     * Statement day: the card's bill was set aside, and paying it is the bill leaving, not spending.
     * Counted as spent, the cycle's figure - the bar, the pace - jumped by the whole bill overnight.
     */
    @Test
    fun `paying a card's bill doesn't move the cycle's figures`() {
        history = history(260_000)
        val before = stateOn(LocalDate.of(2026, 11, 16)).budget!!
        val on = stateOn(LocalDate.of(2026, 11, 17)).budget!!
        // The bill was £142.54. Day to day the estimates shift by pennies and pounds; it moved by
        // the whole bill before.
        assertTrue("before ${before.spendableThisCycle}, on ${on.spendableThisCycle}", kotlin.math.abs(on.spendableThisCycle - before.spendableThisCycle) < 14_254L / 4)
        // The day's own spending, not the bill: £12.80 at a shop (until 0.7.1 wrongly held to be a
        // regular payment, and left out).
        assertTrue("spent today ${on.spentToday}", on.spentToday < 14_254L)
    }
}
