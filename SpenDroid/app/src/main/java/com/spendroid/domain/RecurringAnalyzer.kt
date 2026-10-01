package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object RecurringAnalyzer {

    fun groupKey(tx: TransactionEntity): String {
        val sign = if (tx.amountMinor >= 0) "IN" else "OUT"
        return "$sign|${tx.currency}|${amountBucket(tx.amountMinor)}|${normalize(tx.payee)}"
    }

    fun amountBucket(amountMinor: Long): Long = (Math.abs(amountMinor) + 50L) / 100L

    /**
     * True when [tx] is an occurrence of [rule].
     *
     * Detected rules are keyed by [groupKey], so key equality is exact. Manual rules have a
     * synthetic key that no transaction can ever produce, and a hand-typed payee, so they
     * match on direction, amount and a fuzzy payee comparison instead - "Netflix" has to
     * match a bank's "NETFLIX.COM 1234".
     */
    fun matches(rule: RecurringRule, tx: TransactionEntity): Boolean =
        if (rule.isVariable) {
            // The same payee, the same way, and an amount of the same order - half to double.
            tx.currency == rule.currency &&
                (tx.amountMinor < 0) == (rule.amountMinor < 0) &&
                normalize(tx.payee) == normalize(rule.payee) &&
                abs(tx.amountMinor) * 2 >= abs(rule.amountMinor) &&
                abs(tx.amountMinor) <= abs(rule.amountMinor) * 2
        } else if (rule.isManual) {
            tx.currency == rule.currency &&
                (tx.amountMinor < 0) == (rule.amountMinor < 0) &&
                amountBucket(tx.amountMinor) == amountBucket(rule.amountMinor) &&
                payeeMatches(tx.payee, rule.payee)
        } else {
            groupKey(tx) == rule.key
        }

    private fun payeeMatches(txPayee: String, rulePayee: String): Boolean {
        val a = normalize(txPayee)
        val b = normalize(rulePayee)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    fun analyze(transactions: List<TransactionEntity>): List<RecurringRule> {
        val eligible = transactions.filter { !it.isPending && it.bookingDate.isNotBlank() && it.amountMinor != 0L }
        val exact = eligible.groupBy { groupKey(it) }.values.mapNotNull { detect(it) }
        // A second look, by payee alone, at what the first could not group: the same payee each
        // month for an amount that changes - an energy bill, a salary with overtime. Grouped by
        // amount to the pound, each month was a group of its own and none was ever regular.
        val covered = exact.mapTo(HashSet()) { payeeGroup(it.direction, it.currency, it.payee) }
        val variable = eligible
            .groupBy { payeeGroup(if (it.amountMinor >= 0) Direction.IN else Direction.OUT, it.currency, it.payee) }
            .filterKeys { it !in covered && normalizePayeePart(it).isNotBlank() }
            .values
            .mapNotNull { detectVariable(it) }
        return (exact + variable).sortedByDescending { it.score }
    }

    private fun payeeGroup(direction: Direction, currency: String, payee: String) = "$direction|$currency|${normalize(payee)}"

    private fun normalizePayeePart(group: String) = group.substringAfterLast('|')

    /**
     * A monthly payment whose amount varies, or null. Held tight so everyday spending is never
     * mistaken for a bill: monthly only - a weekly shop at one supermarket would otherwise read
     * as one - at least three months, one payment a month, dates as regular as a fixed monthly
     * payment's, and every amount between half and double the usual.
     */
    private fun detectVariable(txs: List<TransactionEntity>): RecurringRule? {
        val byDate = txs.groupBy { parseBookingDate(it.bookingDate) ?: return null }
        if (byDate.size < MIN_VARIABLE_MONTHS) return null
        val dates = byDate.keys.sorted()
        val amounts = dates.map { date -> byDate.getValue(date).sumOf { it.amountMinor } }
        if (amounts.any { it == 0L } || amounts.map { it < 0 }.distinct().size != 1) return null
        val usual = amounts.map { abs(it) }.sorted().let { it[it.size / 2] }
        if (amounts.any { abs(it) * 2 < usual || abs(it) > usual * 2 }) return null
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
        if (gapFraction(gaps, 24..33) < 0.8f) return null
        // The level to expect next: the middle of the latest three.
        val recent = amounts.takeLast(3).map { abs(it) }.sorted()[1] * (if (amounts.first() < 0) -1 else 1)
        val payee = txs.first().payee
        val direction = if (recent >= 0) Direction.IN else Direction.OUT
        val rule = monthly(VARIABLE_KEY_PREFIX + payeeGroup(direction, txs.first().currency, payee), payee, recent, txs.first().currency, dates)
            ?: return null
        return rule.copy(
            accountIds = txs.mapTo(mutableSetOf()) { it.accountId },
            internalTransfer = txs.all { it.isInternalTransfer },
        )
    }

    private const val MIN_VARIABLE_MONTHS = 3

    fun parseBookingDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value)
        } catch (e: Exception) {
            null
        }

    fun nextOccurrence(rule: RecurringRule, after: LocalDate): LocalDate =
        nextOccurrence(rule, after, WorkingDayCalendar(), PaymentShift.NONE, null)

    /**
     * The next time this payment is expected, moved onto a working day.
     *
     * The stepping is done on nominal dates and the adjustment applied only at the end. Were
     * each step taken from an already-adjusted date, a salary nudged back to Friday would
     * start counting months from the Friday and walk away from the real pay day.
     */
    fun nextOccurrence(
        rule: RecurringRule,
        after: LocalDate,
        calendar: WorkingDayCalendar,
        shift: PaymentShift,
        anchorDayOverride: Int?,
        decemberAnchorDay: Int? = null,
    ): LocalDate {
        // Applied to the nominal date before any working-day adjustment, so an early
        // December pay day still moves off a weekend like any other.
        fun shapeForMonth(date: LocalDate): LocalDate =
            if (date.month == Month.DECEMBER && decemberAnchorDay != null && decemberAnchorDay in 1..31) {
                date.withDayOfMonth(decemberAnchorDay.coerceAtMost(date.lengthOfMonth()))
            } else {
                date
            }
        val effective = anchorDayOverride
            ?.takeIf { it in 1..31 }
            ?.let { rule.copy(anchorDay = it, lastOccurrence = alignTo(rule.lastOccurrence, it)) }
            ?: rule

        var nominal = nextFrom(effective, effective.lastOccurrence)
        var guard = 0
        while (!calendar.adjust(shapeForMonth(nominal), shift).isAfter(after) && guard < MAX_CYCLES) {
            nominal = nextFrom(effective, nominal)
            guard++
        }
        return calendar.adjust(shapeForMonth(nominal), shift)
    }

    /** Puts a remembered date onto the day the user says the payment really falls on. */
    private fun alignTo(date: LocalDate, day: Int): LocalDate =
        date.withDayOfMonth(day.coerceAtMost(date.lengthOfMonth()))

    private const val MAX_CYCLES = 400

    private fun nextFrom(rule: RecurringRule, reference: LocalDate): LocalDate = when (rule.cadence) {
        Cadence.WEEKLY -> reference.plusWeeks(1)
        Cadence.FORTNIGHTLY -> reference.plusDays(14)
        Cadence.QUARTERLY -> reference.plusMonths(3)
        Cadence.ANNUAL -> reference.plusYears(1)
        Cadence.MONTHLY -> aimFor(reference.plusMonths(1), rule.anchorDay)
        Cadence.MONTHLY_LAST_DAY ->
            LocalDate.of(reference.plusMonths(1).year, reference.plusMonths(1).monthValue, 1)
                .plusMonths(1)
                .minusDays(1)
        Cadence.MONTHLY_LAST_BUSINESS_DAY ->
            lastBusinessDayOf(reference.plusMonths(1).year, reference.plusMonths(1).monthValue)
    }

    private fun detect(txs: List<TransactionEntity>): RecurringRule? {
        val dates = txs.mapNotNull { parseBookingDate(it.bookingDate) }.distinct().sorted()
        if (dates.size < 2) return null

        val amounts = txs.map { it.amountMinor }.sorted()
        val amount = amounts[amounts.size / 2]
        if (amount == 0L) return null

        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
        val medianGap = gaps.sorted()[gaps.size / 2]
        val key = groupKey(txs.first())

        // Mostly one weekday, not always: a bank holiday moves a weekly payment a day, and
        // demanding every date agree meant one Easter Monday stopped it being recognised.
        val weekday = usualWeekday(dates)
        val sameWeekday = dates.count { it.dayOfWeek == weekday } >= dates.size * WEEKDAY_SHARE
        val minOccurrences = when {
            medianGap <= 16 -> 3
            medianGap <= 96 -> 2
            else -> 2
        }
        if (dates.size < minOccurrences) return null

        // Which accounts the occurrences came from, so the budget can tell a card
        // subscription from one that really leaves the current account.
        val accountIds = txs.mapTo(mutableSetOf()) { it.accountId }
        val internalTransfer = txs.all { it.isInternalTransfer }

        // The same payment more than once on the same day - £50 to each of two children's
        // accounts. Occurrences are counted by date, so without this the pair read as one
        // £50 payment and the budget came up £50 short every month.
        val perDate = txs.mapNotNull { parseBookingDate(it.bookingDate) }
            .groupingBy { it }
            .eachCount()
            .values
            .sorted()
        val perOccurrence = perDate[perDate.size / 2].coerceAtLeast(1)

        return when {
            sameWeekday && medianGap in 6..9 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.WEEKLY, weekday.value, dates, gapOk = gaps.all { it in 6..9 })

            medianGap in 13..16 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.FORTNIGHTLY, dates.first().dayOfMonth, dates, gapOk = gapFraction(gaps, 13..16) >= 0.5)

            dates.size >= 2 && medianGap in 82..96 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.QUARTERLY, dates.first().dayOfMonth, dates, gapOk = gapFraction(gaps, 82..96) >= 0.5)

            medianGap in 340..385 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.ANNUAL, dates.first().dayOfMonth, dates, gapOk = gapFraction(gaps, 340..385) >= 0.5)

            else -> monthly(key, txs.first().payee, amount, txs.first().currency, dates)
        }?.let { rule ->
            rule.copy(
                accountIds = accountIds,
                internalTransfer = internalTransfer,
                amountMinor = rule.amountMinor * perOccurrence,
                perOccurrence = perOccurrence,
            )
        }
    }

    private fun monthly(key: String, payee: String, amount: Long, currency: String, dates: List<LocalDate>): RecurringRule? {
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
        val consistent = gapFraction(gaps, 24..33)
        if (consistent < 0.6) return null

        val days = dates.map { it.dayOfMonth }
        var cadence = Cadence.MONTHLY
        var anchor = days.groupingBy { it }.eachCount().entries.maxByOrNull { it.value }!!.key

        val nearEnd = days.count { it >= 27 }.toDouble() / days.size
        if (nearEnd >= 0.8) {
            val lastBusiness = dates.count { it == lastBusinessDayOf(it.year, it.monthValue) }.toDouble() / dates.size
            val lastDay = dates.count { it.dayOfMonth == it.lengthOfMonth() }.toDouble() / dates.size
            when {
                lastBusiness >= 0.8 -> cadence = Cadence.MONTHLY_LAST_BUSINESS_DAY
                lastDay >= 0.8 -> cadence = Cadence.MONTHLY_LAST_DAY
            }
        }
        return build(key, payee, amount, currency, cadence, anchor, dates, gapOk = consistent >= 0.8f)
    }

    private fun build(
        key: String,
        payee: String,
        amount: Long,
        currency: String,
        cadence: Cadence,
        anchorDay: Int,
        dates: List<LocalDate>,
        gapOk: Boolean,
    ): RecurringRule {
        val coverage = (dates.size.toFloat() / 12f).coerceIn(0.3f, 1f)
        val regularity = if (gapOk) 1f else 0.5f
        // The multiplication used to bind to the regularity term alone, so the whole score
        // collapsed to 0.4 or 0.2 whatever the evidence - every rule read "40%".
        val score = ((coverage * 0.6f + regularity * 0.4f) * 100f).roundToInt() / 100f
        return RecurringRule(
            key = key,
            payee = payee,
            direction = if (amount >= 0) Direction.IN else Direction.OUT,
            amountMinor = amount,
            currency = currency,
            cadence = cadence,
            anchorDay = anchorDay,
            lastOccurrence = dates.last(),
            occurrences = dates.size,
            score = score,
        )
    }

    /** The weekday most of these dates fall on. */
    private fun usualWeekday(dates: List<LocalDate>): DayOfWeek =
        dates.groupingBy { it.dayOfWeek }.eachCount().maxByOrNull { it.value }!!.key

    /** How many of a weekly payment's dates must share a weekday: all but a holiday or two. */
    private const val WEEKDAY_SHARE = 0.8

    private fun gapFraction(gaps: List<Long>, range: IntRange): Float =
        gaps.count { it in range } / gaps.size.toFloat()

    private fun aimFor(month: LocalDate, day: Int): LocalDate =
        try {
            month.withDayOfMonth(day)
        } catch (e: Exception) {
            month.withDayOfMonth(month.lengthOfMonth())
        }

    private fun lastBusinessDayOf(year: Int, month: Int): LocalDate {
        var day = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
            day = day.minusDays(1)
        }
        return day
    }

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("\\s+"), " ")

    /** A payee as rules compare them: case and spacing ignored. */
    fun normalizedPayee(value: String): String = normalize(value)

    fun detectRecurring(transactions: List<TransactionEntity>): Map<String, Boolean> {
        val rules = analyze(transactions)
        val txIdsInRules = mutableSetOf<String>()
        rules.forEach { rule ->
            transactions
                .filter { if (rule.isVariable) matches(rule, it) else groupKey(it) == rule.key }
                .forEach { txIdsInRules.add("${it.accountId}|${it.transactionId}") }
        }
        return transactions.associateBy({ "${it.accountId}|${it.transactionId}" }) { txIdsInRules.contains("${it.accountId}|${it.transactionId}") }
    }

    data class RecurringCandidate(
        val payee: String,
        val direction: Direction,
        val amountMinor: Long,
        val currency: String,
        val cadence: Cadence,
        val anchorDay: Int,
        val startDate: LocalDate,
        val occurrenceCount: Int,
        val sampleTransactions: List<TransactionEntity>,
    )

    fun findCandidates(transactions: List<TransactionEntity>): List<RecurringCandidate> =
        transactions
            .filter { !it.isPending && it.bookingDate.isNotBlank() && it.amountMinor != 0L && !it.isInternalTransfer }
            .groupBy { groupKey(it) }
            .values
            .filter { it.size >= 2 }
            .mapNotNull { txs ->
                val dates = txs.mapNotNull { parseBookingDate(it.bookingDate) }.distinct().sorted()
                if (dates.size < 2) return@mapNotNull null

                val amounts = txs.map { it.amountMinor }.sorted()
                val amount = amounts[amounts.size / 2]
                if (amount == 0L) return@mapNotNull null

                val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
                val medianGap = gaps.sorted()[gaps.size / 2]

                val cadence = when {
                    dates.count { it.dayOfWeek == usualWeekday(dates) } >= dates.size * WEEKDAY_SHARE && medianGap in 6..9 -> Cadence.WEEKLY
                    medianGap in 13..16 -> Cadence.FORTNIGHTLY
                    medianGap in 24..33 -> Cadence.MONTHLY
                    medianGap in 82..96 -> Cadence.QUARTERLY
                    medianGap in 340..385 -> Cadence.ANNUAL
                    else -> Cadence.MONTHLY
                }

                val anchorDay = when (cadence) {
                    Cadence.WEEKLY -> usualWeekday(dates).value
                    Cadence.FORTNIGHTLY -> dates.first().dayOfMonth
                    Cadence.MONTHLY -> dates.map { it.dayOfMonth }.groupingBy { it }.eachCount().entries.maxByOrNull { it.value }?.key ?: dates.first().dayOfMonth
                    Cadence.QUARTERLY, Cadence.ANNUAL -> dates.first().dayOfMonth
                    else -> dates.first().dayOfMonth
                }

                RecurringCandidate(
                    payee = txs.first().payee,
                    direction = if (amount >= 0) Direction.IN else Direction.OUT,
                    amountMinor = amount,
                    currency = txs.first().currency,
                    cadence = cadence,
                    anchorDay = anchorDay,
                    startDate = dates.first(),
                    occurrenceCount = dates.size,
                    sampleTransactions = txs,
                )
            }
            .let { candidates ->
                // Once for all of them: this ran the whole analysis again for every candidate.
                val rules = analyze(transactions)
                candidates.filter { !isAlreadyRecurring(it, rules) }
            }
            .distinctBy { "${it.payee}|${it.amountMinor}|${it.currency}|${it.cadence}" }

    private fun isAlreadyRecurring(candidate: RecurringCandidate, rules: List<RecurringRule>): Boolean {
        return rules.any { rule ->
            rule.payee == candidate.payee &&
            // A candidate is one payment; a rule may be several on the same day.
            rule.amountMinor / rule.perOccurrence == candidate.amountMinor &&
            rule.currency == candidate.currency &&
            rule.cadence == candidate.cadence
        }
    }
}