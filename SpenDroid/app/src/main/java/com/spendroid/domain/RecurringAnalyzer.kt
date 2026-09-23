package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
        if (rule.isManual) {
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

    fun analyze(transactions: List<TransactionEntity>): List<RecurringRule> =
        transactions
            .filter { !it.isPending && it.bookingDate.isNotBlank() && it.amountMinor != 0L }
            .groupBy { groupKey(it) }
            .values
            .mapNotNull { detect(it) }
            .sortedByDescending { it.score }

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
    ): LocalDate {
        val effective = anchorDayOverride
            ?.takeIf { it in 1..31 }
            ?.let { rule.copy(anchorDay = it, lastOccurrence = alignTo(rule.lastOccurrence, it)) }
            ?: rule

        var nominal = nextFrom(effective, effective.lastOccurrence)
        var guard = 0
        while (!calendar.adjust(nominal, shift).isAfter(after) && guard < MAX_CYCLES) {
            nominal = nextFrom(effective, nominal)
            guard++
        }
        return calendar.adjust(nominal, shift)
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

        val sameWeekday = dates.all { it.dayOfWeek == dates.first().dayOfWeek }
        val minOccurrences = when {
            medianGap <= 16 -> 3
            medianGap <= 96 -> 2
            else -> 2
        }
        if (dates.size < minOccurrences) return null

        return when {
            sameWeekday && medianGap in 6..9 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.WEEKLY, dates.first().dayOfWeek.value, dates, gapOk = gaps.all { it in 6..9 })

            medianGap in 13..16 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.FORTNIGHTLY, dates.first().dayOfMonth, dates, gapOk = gapFraction(gaps, 13..16) >= 0.5)

            dates.size >= 2 && medianGap in 82..96 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.QUARTERLY, dates.first().dayOfMonth, dates, gapOk = gapFraction(gaps, 82..96) >= 0.5)

            medianGap in 340..385 ->
                build(key, txs.first().payee, amount, txs.first().currency, Cadence.ANNUAL, dates.first().dayOfMonth, dates, gapOk = gapFraction(gaps, 340..385) >= 0.5)

            else -> monthly(key, txs.first().payee, amount, txs.first().currency, dates)
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

    fun detectRecurring(transactions: List<TransactionEntity>): Map<String, Boolean> {
        val rules = analyze(transactions)
        val txIdsInRules = mutableSetOf<String>()
        rules.forEach { rule ->
            transactions
                .filter { groupKey(it) == rule.key }
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
                    dates.all { it.dayOfWeek == dates.first().dayOfWeek } && medianGap in 6..9 -> Cadence.WEEKLY
                    medianGap in 13..16 -> Cadence.FORTNIGHTLY
                    medianGap in 24..33 -> Cadence.MONTHLY
                    medianGap in 82..96 -> Cadence.QUARTERLY
                    medianGap in 340..385 -> Cadence.ANNUAL
                    else -> Cadence.MONTHLY
                }

                val anchorDay = when (cadence) {
                    Cadence.WEEKLY -> dates.first().dayOfWeek.value
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
            .filter { !isAlreadyRecurring(it, transactions) }
            .distinctBy { "${it.payee}|${it.amountMinor}|${it.currency}|${it.cadence}" }

    private fun isAlreadyRecurring(candidate: RecurringCandidate, transactions: List<TransactionEntity>): Boolean {
        val rules = analyze(transactions)
        return rules.any { rule ->
            rule.payee == candidate.payee &&
            rule.amountMinor == candidate.amountMinor &&
            rule.currency == candidate.currency &&
            rule.cadence == candidate.cadence
        }
    }
}