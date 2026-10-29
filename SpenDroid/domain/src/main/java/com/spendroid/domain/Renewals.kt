package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Things that end and renew - car insurance, breakdown cover, a TV licence - kept by the name the
 * user gives them, not by who they're with or what they cost: the provider and the price can both
 * change at each renewal, and that's the point of being reminded.
 *
 * Nothing is set aside for them and nothing in the budget changes: the user is told once, a few
 * weeks before the cover ends, in time to compare. When a payment that looks like it turns up
 * around then, Penny asks whether that was it - and if so, when the new cover ends, which is the
 * next date she reminds about. She never takes the payment's date for the end date.
 */
object Renewals {

    enum class Paid { YEARLY, MONTHLY }

    /** How long before the cover ends to be reminded. */
    enum class Remind(val label: String) {
        FOUR_WEEKS("4 weeks before"),
        SIX_WEEKS("6 weeks before"),
        TWO_MONTHS("2 months before");

        fun on(ends: LocalDate): LocalDate = when (this) {
            FOUR_WEEKS -> ends.minusWeeks(4)
            SIX_WEEKS -> ends.minusWeeks(6)
            TWO_MONTHS -> ends.minusMonths(2)
        }
    }

    /** A payment the user said was it renewing. */
    data class Payment(val key: String, val date: LocalDate, val payee: String, val amountMinor: Long)

    /** What the user has said about one. */
    data class Renewal(
        val id: String,
        val name: String,
        val ends: LocalDate,
        val remind: Remind = Remind.SIX_WEEKS,
        val paid: Paid = Paid.YEARLY,
        /** Roughly what it cost last time, if they said. */
        val lastMinor: Long? = null,
        /** "Remind me in 2 weeks": nothing said before this. */
        val snoozedUntil: LocalDate? = null,
        /** "Sorted" for this end date: nothing more said until the next. */
        val sorted: Boolean = false,
        /** Payments the user said were it renewing, newest first. */
        val renewedBy: List<Payment> = emptyList(),
        /** Payments the user said weren't it, by [txKey]. */
        val notIt: Set<String> = emptySet(),
    ) {
        val remindOn: LocalDate get() = remind.on(ends)
    }

    /** Where one stands today. */
    sealed interface Status {
        /** Its reminder isn't due yet. */
        data class Later(val daysLeft: Long) : Status

        /** Reminder time, and not yet dealt with: Penny says so, in amber. */
        data class Due(val daysLeft: Long) : Status

        /** Reminder time, but "Sorted", or put off for now. */
        data class Quiet(val daysLeft: Long) : Status

        /** The cover has ended, and no new end date is known: Penny asks for one. */
        data class Ended(val daysAgo: Long) : Status
    }

    /** One renewal, where it stands, and a payment that looks like it to ask about. */
    data class Item(val renewal: Renewal, val status: Status, val match: Payment? = null) {
        /** Something for the user to do: Penny's amber lines are these. */
        val needsYou: Boolean get() = status is Status.Due || status is Status.Ended
    }

    /** How long after the end a renewal's payment can still turn up - and the reminder stays. */
    private const val LATE_DAYS = 21L

    /** A payment to the same payee this long before means it's paid regularly: a week to six. */
    private const val REGULAR_GAP_MIN = 6L
    private const val REGULAR_GAP_MAX = 45L

    fun status(r: Renewal, today: LocalDate): Status {
        val daysLeft = ChronoUnit.DAYS.between(today, r.ends)
        return when {
            daysLeft < 0 -> Status.Ended(-daysLeft)
            today.isBefore(r.remindOn) -> Status.Later(daysLeft)
            r.sorted || (r.snoozedUntil != null && today.isBefore(r.snoozedUntil)) -> Status.Quiet(daysLeft)
            else -> Status.Due(daysLeft)
        }
    }

    /** Every renewal, soonest first, each with where it stands and any payment to ask about. */
    fun all(renewals: List<Renewal>, transactions: List<TransactionEntity>, today: LocalDate): List<Item> {
        // A payment is one renewal's at most: one already said to renew another isn't asked about
        // again, and two ending together don't both ask about the same one - the sooner asks.
        val taken = renewals.flatMap { r -> r.renewedBy.map { it.key } }.toMutableSet()
        return renewals.sortedBy { it.ends }.map { r ->
            val match = lookalike(r, transactions, today, taken)
            match?.let { taken += it.key }
            Item(r, status(r, today), match)
        }
    }

    /** How a transaction is known again: the same key for the same payment, sync after sync. */
    fun txKey(tx: TransactionEntity): String = "${tx.accountId}|${tx.transactionId}"

    /**
     * A payment out that looks like [r] renewing, from a little before its reminder to three weeks
     * after its end: to a payee it was renewed with before, or one named like it, or - for
     * insurance or cover - to an insurer. The latest one not already answered.
     */
    fun lookalike(r: Renewal, transactions: List<TransactionEntity>, today: LocalDate, taken: Set<String> = emptySet()): Payment? {
        val from = r.remindOn.minusWeeks(2)
        val to = minOf(r.ends.plusDays(LATE_DAYS), today)
        val answered = r.notIt + r.renewedBy.map { it.key } + taken
        // Payees paid regularly - a monthly insurance direct debit - by when each was paid: one
        // paid a month or so before too is that, carrying on, not something renewing.
        val paidOn = transactions.asSequence()
            .filter { !it.isPending && it.amountMinor < 0 }
            .mapNotNull { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { RecurringAnalyzer.normalizedPayee(tx.payee) to it } }
            .groupBy({ it.first }, { it.second })
        fun carryingOn(tx: TransactionEntity, on: LocalDate): Boolean =
            paidOn[RecurringAnalyzer.normalizedPayee(tx.payee)].orEmpty().any { it.isBefore(on.minusDays(REGULAR_GAP_MIN)) && !it.isBefore(on.minusDays(REGULAR_GAP_MAX)) }
        val before = r.renewedBy.map { RecurringAnalyzer.normalizedPayee(it.payee) }.toSet()
        val stems = stemsOf(r.name)
        val insurance = INSURANCE_WORDS.any { r.name.lowercase().contains(it) }
        return transactions.asSequence()
            .filter { !it.isPending && !it.isInternalTransfer && !it.isCardPayment && it.amountMinor < 0 }
            .filter { txKey(it) !in answered }
            .mapNotNull { tx -> RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { tx to it } }
            .filter { (_, d) -> !d.isBefore(from) && !d.isAfter(to) }
            .filter { (tx, d) -> !carryingOn(tx, d) }
            .filter { (tx, _) ->
                val payee = tx.payee.lowercase()
                RecurringAnalyzer.normalizedPayee(tx.payee) in before ||
                    stems.any { payee.contains(it) } ||
                    (insurance && INSURERS.any { payee.contains(it) })
            }
            .maxByOrNull { it.second }
            ?.let { (tx, d) -> Payment(txKey(tx), d, tx.payee, -tx.amountMinor) }
    }

    /**
     * The new end dates to offer once a payment was it: a year on from the old end - the usual -
     * and a year on from the payment, if that's a different day. Any other, from the calendar.
     */
    fun nextEnds(r: Renewal, paid: LocalDate): List<LocalDate> =
        listOf(r.ends.plusYears(1), paid.plusYears(1)).distinct()

    /** The most recent change in price between renewals, newest less the one before: up is positive. */
    fun change(r: Renewal): Long? {
        val (latest, before) = r.renewedBy.sortedByDescending { it.date }.take(2).takeIf { it.size == 2 } ?: return null
        return latest.amountMinor - before.amountMinor
    }

    /** Something with quotes to compare - insurance, cover - rather than a fixed price like a TV licence. */
    fun compares(r: Renewal): Boolean = INSURANCE_WORDS.any { r.name.lowercase().contains(it) }

    /** "Car insurance" -> the parts of its name a payee might carry: "car" is too short to tell. */
    private fun stemsOf(name: String): List<String> = name.lowercase()
        .split(Regex("[^a-z]+"))
        .filter { it.length >= 4 }
        .map { it.take(5) }
        .filter { it !in GENERIC }

    /** Words in a name that say nothing of who it's with. */
    private val GENERIC = setOf("insur", "cover", "renew", "polic", "annua", "yearl", "month", "premi")

    private val INSURANCE_WORDS = listOf("insurance", "cover", "breakdown", "warranty", "assurance")

    /** UK insurers and breakdown services, as they show on a statement. */
    private val INSURERS = listOf(
        "insur", "assur", "admiral", "aviva", "direct line", "churchill", "hastings", "esure", "saga",
        "axa", "allianz", "zurich", "ageas", "lv=", "liverpool victoria", "more than", "rac ", "rac motoring",
        "green flag", "the aa", "aa breakdown", "privilege", "sheilas", "elephant", "swiftcover", "bupa",
        "petplan", "legal & general", "royal london", "vitality", "nfu mutual", "markerstudy", "1st central",
    )

    /** One the user might add with a tap, from the empty Renewals tab. */
    val USUAL = listOf("Car insurance", "Home insurance", "Breakdown cover", "Pet insurance", "TV licence", "Travel insurance")
}
