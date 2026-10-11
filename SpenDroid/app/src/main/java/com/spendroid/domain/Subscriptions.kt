package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Which regular payments are subscriptions - streaming, software, the gym - rather than the
 * household's bills, and what each comes to: a year, in all, since when, whether it's still
 * going, and when a yearly one renews. Read from the same regular payments as Regular.
 */
object Subscriptions {

    enum class Kind { SUBSCRIPTION, HOUSEHOLD }

    data class Item(
        val rule: RecurringRule,
        val kind: Kind,
        /** Guessed, rather than said by the user. */
        val guessed: Boolean,
        val yearlyMinor: Long,
        val paidInAllMinor: Long,
        val since: LocalDate?,
        /** Where it's paid from, said plainly: "Nectar Card", "via PayPal". */
        val paidFrom: String?,
        /** Missed two payments in a row: cancelled, or the card it was on expired. */
        val lapsed: Boolean,
        val next: LocalDate?,
    )

    /** Names that are subscriptions wherever they're paid from. */
    private val SUBSCRIPTION_WORDS = listOf(
        "netflix", "spotify", "disney", "prime video", "amazon prime", "apple.com", "apple music", "icloud", "itunes",
        "google one", "google storage", "youtube", "microsoft", "xbox", "playstation", "nintendo", "steam",
        "adobe", "dropbox", "cloudflare", "patreon", "audible", "kindle", "now tv", "nowtv", "deezer", "tidal",
        "paramount", "crunchyroll", "dazn", "britbox", "discovery+", "chatgpt", "openai", "anthropic", "github",
        "1password", "lastpass", "nordvpn", "expressvpn", "surfshark", "duolingo", "headspace", "calm.com", "strava",
        "gym", "fitness", "puregym", "the gym", "peloton", "easynews", "usenet", "newsgroup", "plex",
        "membership", "subscription", "times newspapers", "guardian", "telegraph", "financial times", "economist",
        "zwift", "onlyfans", "twitch", "canva", "notion", "evernote", "proton",
    )

    /** Names that are household bills, whatever else they look like. */
    private val HOUSEHOLD_WORDS = listOf(
        "mortgage", "rent", "council", "water", "energy", "electric", "gas", "octopus", "british gas", "edf", "e.on", "eon ",
        "ovo", "scottish power", "insurance", "assurance", "admiral", "aviva", "direct line", "tv licen", "broadband",
        "bt group", "virgin media", "sky digital", "plusnet", "talktalk", "vodafone", " ee ", "o2", "three", "giffgaff",
        "voxi", "tesco mobile", "id mobile", "loan", "finance", "nursery", "school", "childcare", "pension",
    )

    /** What it is, as the user said or as best guessed. */
    fun kind(rule: RecurringRule, payee: String, viaCardOrPayPal: Boolean, choice: Kind?): Pair<Kind, Boolean> {
        if (choice != null) return choice to false
        val name = " ${payee.lowercase()} "
        if (HOUSEHOLD_WORDS.any { it in name }) return Kind.HOUSEHOLD to true
        if (SUBSCRIPTION_WORDS.any { it in name }) return Kind.SUBSCRIPTION to true
        // Small, steady, and on a card or through PayPal: most such payments are subscriptions.
        val small = abs(rule.amountMinor) <= SMALL_MINOR
        return (if (viaCardOrPayPal && small && !rule.isVariable) Kind.SUBSCRIPTION else Kind.HOUSEHOLD) to true
    }

    /**
     * Every regular payment out, sorted into subscriptions and household bills, with what each
     * comes to. PayPal's own copy of a payment it passes on, and transfers, aren't payments.
     */
    fun all(
        rules: List<RecurringRule>,
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>,
        choices: Map<String, Kind>,
        today: LocalDate,
    ): Map<String, Item> {
        val payPal = accounts.filter { it.accountType == AccountType.PAYPAL }.map { it.id }.toSet()
        val cards = accounts.filter { it.accountType == AccountType.CREDIT_CARD }.map { it.id }.toSet()
        return rules.asSequence()
            .filter { it.direction == Direction.OUT && !it.internalTransfer && !it.key.startsWith(CARD_BILL_KEY_PREFIX) }
            .filter { rule -> rule.accountIds.isEmpty() || rule.accountIds.any { it !in payPal } }
            .mapNotNull { rule ->
                val payee = RecurringAnalyzer.normalizedPayee(rule.payee)
                val own = transactions.filter {
                    it.amountMinor < 0 && it.accountId !in payPal && !it.isPending &&
                        RecurringAnalyzer.normalizedPayee(it.payee) == payee
                }
                val history = PriceChanges.history(rule, own)
                val account = rule.paidFrom ?: rule.accountIds.firstOrNull { it !in payPal }
                val viaPayPal = own.maxByOrNull { it.bookingDate }?.description?.startsWith("via PayPal") == true
                val (kind, guessed) = kind(rule, rule.payee, viaPayPal || account in cards, choices[rule.key])
                val next = if (rule.isManual || !lapsed(rule, today)) RecurringAnalyzer.nextOccurrence(rule, today.minusDays(1)) else null
                rule.key to Item(
                    rule = rule,
                    kind = kind,
                    guessed = guessed,
                    yearlyMinor = abs(rule.amountMinor) * timesAYear(rule.cadence),
                    paidInAllMinor = history.sumOf { it.amountMinor },
                    since = history.firstOrNull()?.date,
                    paidFrom = when {
                        viaPayPal -> "via PayPal"
                        else -> accounts.firstOrNull { it.id == account }?.label?.trim()
                    },
                    lapsed = !rule.isManual && lapsed(rule, today),
                    next = next,
                )
            }
            .toMap()
    }

    /** Missed two payments in a row - not seen for over two of its gaps. */
    fun lapsed(rule: RecurringRule, today: LocalDate): Boolean =
        ChronoUnit.DAYS.between(rule.lastOccurrence, today) > gapDays(rule.cadence) * 2 + 5

    /** Yearly payments - subscription or not - falling due in the next [days]. */
    fun yearlyComing(items: Collection<Item>, today: LocalDate, days: Long = 60): List<Item> =
        items.filter { it.rule.cadence == Cadence.ANNUAL && !it.lapsed && it.next != null && !it.next.isAfter(today.plusDays(days)) }
            .sortedBy { it.next }

    fun timesAYear(cadence: Cadence): Long = when (cadence) {
        Cadence.WEEKLY -> 52
        Cadence.FORTNIGHTLY -> 26
        Cadence.MONTHLY, Cadence.MONTHLY_LAST_DAY, Cadence.MONTHLY_LAST_BUSINESS_DAY -> 12
        Cadence.QUARTERLY -> 4
        Cadence.ANNUAL -> 1
    }

    private fun gapDays(cadence: Cadence): Long = when (cadence) {
        Cadence.WEEKLY -> 7
        Cadence.FORTNIGHTLY -> 14
        Cadence.MONTHLY, Cadence.MONTHLY_LAST_DAY, Cadence.MONTHLY_LAST_BUSINESS_DAY -> 31
        Cadence.QUARTERLY -> 92
        Cadence.ANNUAL -> 366
    }

    /** Up to £30 a time: subscription-sized. */
    private const val SMALL_MINOR = 3_000L
}
