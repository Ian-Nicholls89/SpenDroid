package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Reading card payments out of other apps' notifications, and handing them over to the bank's
 * own record when it arrives. Pure logic: the listener service does the Android side.
 *
 * The rule throughout is that a notification may only ever make the figures earlier, never
 * wrong. Anything unclear - two amounts, a refund, money coming in, a foreign currency - is
 * left for the bank sync to report as it always has.
 */
object NotificationSpend {

    const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"

    /** What became of a notification, for the log. */
    enum class Outcome(val label: String) {
        COUNTED("counted"),
        DUPLICATE("same purchase as another"),
        ACCOUNTED_FOR("already a bill or transfer"),
        NOT_A_PAYMENT("not a payment"),
        PAUSED("not counted: card alerts paused"),
    }

    /**
     * Seen spends that repeat an earlier one, by the same rule as [duplicateOf] - for tidying up
     * after the fact. Oldest first, so the first announcement is the one kept.
     */
    fun repeats(seen: List<SeenSpendEntity>): List<SeenSpendEntity> = repeatPairs(seen).map { it.second }

    /** Each repeat with the earlier announcement it repeats. */
    fun repeatPairs(seen: List<SeenSpendEntity>): List<Pair<SeenSpendEntity, SeenSpendEntity>> {
        val kept = mutableListOf<SeenSpendEntity>()
        val repeats = mutableListOf<Pair<SeenSpendEntity, SeenSpendEntity>>()
        seen.filter { !it.dismissed }.sortedBy { it.seenAt }.forEach { spend ->
            val first = duplicateOf(spend.source, -spend.amountMinor, spend.cardDigits, spend.seenAt, kept)
            if (first != null) repeats += first to spend else kept += spend
        }
        return repeats
    }

    /**
     * The announcements to drop as repeats of one purchase: the later one - unless the bank has
     * already taken that one over, and then the earlier, still counted on its own. One the bank
     * reported is never dropped.
     */
    fun toDrop(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        repeatPairs(seen).mapNotNull { (first, repeat) ->
            when {
                repeat.matchedTransactionId == null -> repeat
                first.matchedTransactionId == null -> first
                else -> null
            }
        }.distinctBy { it.id }

    /** One bank app the user picked, and the accounts it speaks for. */
    data class Source(
        val packageName: String,
        val label: String,
        val accountIds: List<String>,
        /** With [Unsure.ALWAYS], the account used; otherwise where a new shop is guessed to be. */
        val defaultAccountId: String? = null,
        val unsure: Unsure = Unsure.SMART,
    )

    /**
     * What to do when an app covering several accounts doesn't say which one paid: first
     * direct names no account at all, and covers personal and joint alike.
     */
    enum class Unsure {
        /** A shop always paid from one account goes there; anything else asks. */
        SMART,
        /** Every payment asks. */
        ASK,
        /** Every payment goes where that shop is usually paid from, or the default. */
        GUESS,
        /** Every payment goes to the default account. */
        ALWAYS,
    }

    data class Parsed(
        /** Positive: what was spent. */
        val amountMinor: Long,
        val merchant: String,
        val cardDigits: String?,
    )

    /** Money coming in, refunds, refusals and balance news: never a spend. */
    private val NOT_A_SPEND = Regex(
        """\b(declined|refund(ed)?|received|credited|paid you|sent you|incoming|money in|balance|failed|reversed|""" +
            """cancel(l)?ed|top.?up|salary|deposit(ed)?|payment due|statement|interest|reminder|limit)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Wording a bank uses for a card payment. A bank notification without any is not read. */
    private val SPEND_WORDS = Regex(
        """\b(spent|spend|payment|paid|purchase|card|transaction|debit|contactless|money out)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val POUNDS = Regex("""(?:£|GBP\s?)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?""")
    private val OTHER_CURRENCY = Regex("""[€$¥]|\b(EUR|USD)\b""")
    private val CARD_DIGITS = Regex("""(?:[•*·xX]{2,}|ending(?: in)?)\s?(\d{4})\b""")
    private val AT_MERCHANT = Regex(
        """\b(?:at|to)\s+([A-Za-z0-9][^.,\n£]{0,40}?)(?=\s+(?:on|using|with|for|from|via)\b|[.,\n]|$)""",
    )

    /**
     * A wording the user taught from one of their own notifications: count ones like it, or
     * ignore them. Checked before the built-in rules, so a bank that words things its own way
     * needs one tap rather than an update.
     */
    data class Learned(val source: String, val pattern: String, val count: Boolean)

    /** The notification's title and text as one piece, the way every rule reads it. */
    fun bodyOf(title: String?, text: String?): String = listOfNotNull(title, text).joinToString("\n").trim()

    /**
     * Learns a wording from one notification. The amount becomes any amount and runs of digits
     * any digits (card numbers, dates, times); the rest must read the same. To count, the payee
     * becomes any payee - the wording is what is learned. To ignore, the payee stays: ignoring
     * one "Money out … to PREMIUM BONDS" must not ignore every "Money out".
     * Null when the notification has no single amount to learn around.
     */
    fun learn(source: String, title: String?, text: String?, count: Boolean): Learned? {
        val body = bodyOf(title, text)
        val amounts = POUNDS.findAll(body).toList()
        val amount = amounts.singleOrNull() ?: return null
        val merchant = merchantSpan(body, amount.range.last + 1)
        val pattern = buildString {
            append('^')
            append(literal(body.substring(0, amount.range.first)))
            append(AMOUNT_GROUP)
            if (merchant != null && count) {
                append(literal(body.substring(amount.range.last + 1, merchant.first)))
                append(if (merchant.last + 1 >= body.length) "(.+)" else "(.+?)")
                append(literal(body.substring(merchant.last + 1)))
            } else {
                append(literal(body.substring(amount.range.last + 1)))
            }
            append('$')
        }
        return Learned(source, pattern, count)
    }

    /** Where the payee sits after the amount: past "to", "at", "from", a comma or a colon. */
    private fun merchantSpan(body: String, from: Int): IntRange? {
        val rest = body.substring(from)
        val lead = Regex("""^\s*(?:[,:\-–]\s*|\b(?:to|at|from|with)\s+)""", RegexOption.IGNORE_CASE).find(rest) ?: return null
        val start = from + lead.range.last + 1
        if (start >= body.length) return null
        // Up to " on …", " using …", a full stop or the end of the line.
        val end = Regex("""\s+(?:on|using|via|with)\b|[.\n]""", RegexOption.IGNORE_CASE)
            .find(body, start)?.range?.first ?: body.length
        return if (end - start >= 2) start until end else null
    }

    private fun literal(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> {
                    while (i < text.length && text[i].isDigit()) i++
                    out.append("\\d+")
                    continue
                }
                c.isWhitespace() -> {
                    while (i < text.length && text[i].isWhitespace()) i++
                    out.append("\\s+")
                    continue
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return out.toString()
    }

    private const val AMOUNT_GROUP = """(?:£|GBP\s?)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?"""

    /**
     * The learned wordings first, then the built-in rules. An "ignore" the user taught wins over
     * everything; a "count" is read by its own pattern.
     */
    fun parse(source: String, title: String?, text: String?, learned: List<Learned>): Parsed? {
        val body = bodyOf(title, text)
        learned.filter { it.source == source }.forEach { rule ->
            val match = runCatching { Regex(rule.pattern).find(body) }.getOrNull() ?: return@forEach
            if (!rule.count) return null
            val pounds = match.groupValues[1].replace(",", "").toLongOrNull() ?: return@forEach
            val amount = pounds * 100 + match.groupValues[2].ifEmpty { "0" }.toLong()
            if (amount <= 0L) return@forEach
            val merchant = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() } ?: "Payment"
            return Parsed(amount, merchant, CARD_DIGITS.find(body)?.groupValues?.get(1))
        }
        return parse(source, title, text)
    }

    fun parse(source: String, title: String?, text: String?): Parsed? {
        val body = bodyOf(title, text)
        if (body.isEmpty() || NOT_A_SPEND.containsMatchIn(body) || OTHER_CURRENCY.containsMatchIn(body)) return null
        // One amount only: "£3.20 of your £50 budget" says two things, and guessing is how a
        // figure goes wrong.
        val amounts = POUNDS.findAll(body).map { toMinor(it) }.distinct().toList()
        val amount = amounts.singleOrNull()?.takeIf { it > 0L } ?: return null
        val digits = CARD_DIGITS.find(body)?.groupValues?.get(1)

        val merchant = if (source == GOOGLE_WALLET) {
            // Wallet puts the merchant in the title and the amount and card below it.
            title?.trim()?.takeIf { it.isNotEmpty() && !POUNDS.containsMatchIn(it) }
                ?: merchantFrom(body)
        } else {
            if (!SPEND_WORDS.containsMatchIn(body)) return null
            merchantFrom(body)
        } ?: "Card payment"
        return Parsed(amount, merchant.trim(), digits)
    }

    private fun merchantFrom(body: String): String? =
        AT_MERCHANT.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }

    private fun toMinor(match: MatchResult): Long {
        val pounds = match.groupValues[1].replace(",", "").toLong()
        val pence = match.groupValues[2].ifEmpty { "0" }.toLong()
        return pounds * 100 + pence
    }

    /**
     * Where a payment goes. [accountId] null means the user is asked; until they answer it is
     * counted on [provisionalAccountId], the likeliest, as the money has gone either way.
     */
    data class Placement(val accountId: String?, val guessed: Boolean, val provisionalAccountId: String? = null)

    /** The accounts a payment from [source] could have come from. Null for an app not set up. */
    fun candidatesFor(source: String, sources: List<Source>, accounts: List<AccountEntity>): List<AccountEntity>? =
        if (source == GOOGLE_WALLET) {
            accounts.filter { it.walletLinked }
        } else {
            sources.firstOrNull { it.packageName == source }?.let { mapped -> mapped.accountIds.mapNotNull { id -> accounts.firstOrNull { it.id == id } } }
        }

    /** The key a shop's chosen account is remembered under: the app, and the shop however it's spaced. */
    fun placeKey(source: String, merchant: String) = source + "|" + normalise(merchant)

    /**
     * Which account a payment was made from. Settled when the notification shows the card's
     * digits, the app covers one account, or the user said where this shop goes ([places], by
     * [placeKey]). Otherwise as the app's [Unsure] says: under [Unsure.SMART], a shop paid at
     * least [CONFIDENT_VISITS] times, always from the same account, goes there as a guess, and
     * anything else is asked. [history] is what has been paid from where, the user's own
     * answers included.
     */
    fun placementFor(
        source: String,
        parsed: Parsed,
        sources: List<Source>,
        accounts: List<AccountEntity>,
        history: List<TransactionEntity>,
        places: Map<String, String> = emptyMap(),
    ): Placement? {
        val candidates = candidatesFor(source, sources, accounts) ?: return null
        if (candidates.isEmpty()) return null
        parsed.cardDigits?.let { digits ->
            candidates.firstOrNull { it.cardLastFour == digits || it.identity?.endsWith(digits) == true }
                ?.let { return Placement(it.id, guessed = false) }
        }
        candidates.singleOrNull()?.let { return Placement(it.id, guessed = false) }
        places[placeKey(source, parsed.merchant)]?.takeIf { id -> candidates.any { it.id == id } }
            ?.let { return Placement(it, guessed = false) }

        val setup = sources.firstOrNull { it.packageName == source }
        val mode = setup?.unsure ?: Unsure.SMART
        val default = setup?.defaultAccountId?.takeIf { id -> candidates.any { it.id == id } }
        if (mode == Unsure.ALWAYS && default != null) return Placement(default, guessed = false)

        val payee = normalise(parsed.merchant)
        val visits = history
            .filter { it.accountId in candidates.map { c -> c.id } && it.amountMinor < 0 && payeeMatches(normalise(it.payee), payee) }
            .groupingBy { it.accountId }
            .eachCount()
        val usual = visits.maxByOrNull { it.value }?.key
        val likeliest = usual ?: default ?: candidates.first().id
        val confident = visits.size == 1 && visits.values.single() >= CONFIDENT_VISITS
        return when {
            mode == Unsure.GUESS || mode == Unsure.ALWAYS -> Placement(likeliest, guessed = true)
            mode == Unsure.SMART && confident -> Placement(likeliest, guessed = true)
            else -> Placement(null, guessed = false, provisionalAccountId = likeliest)
        }
    }

    /** Paid this many times, always from one account: sure enough not to ask. */
    const val CONFIDENT_VISITS = 3

    private fun normalise(value: String) = value.lowercase().replace(Regex("""\s+"""), " ").trim()

    private fun payeeMatches(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && (a == b || a.contains(b) || b.contains(a))

    /**
     * Whether the budget already accounts for this payment some other way, so counting it from a
     * notification would count it twice: a payee the history shows is always money moved between
     * the user's own accounts, or a payment that is one of their regular bills.
     */
    fun alreadyAccountedFor(parsed: Parsed, history: List<TransactionEntity>, rules: List<RecurringRule>): Boolean {
        val payee = normalise(parsed.merchant)
        val same = history.filter { it.amountMinor < 0 && payeeMatches(normalise(it.payee), payee) }
        if (same.isNotEmpty() && same.count { it.isInternalTransfer } * 2 >= same.size) return true
        val probe = TransactionEntity(
            accountId = "", transactionId = "", bookingDate = "", valueDate = null, amountMinor = -parsed.amountMinor,
            currency = "GBP", payee = parsed.merchant, description = null, isPending = true, rawJson = null,
        )
        return rules.any { rule ->
            rule.direction == Direction.OUT && (
                RecurringAnalyzer.matches(rule, probe) ||
                    (RecurringAnalyzer.amountBucket(rule.amountMinor) == RecurringAnalyzer.amountBucket(probe.amountMinor) &&
                        payeeMatches(normalise(rule.payee), payee))
                )
        }
    }

    fun accountFor(
        source: String,
        parsed: Parsed,
        sources: List<Source>,
        accounts: List<AccountEntity>,
    ): String? {
        val candidates = if (source == GOOGLE_WALLET) {
            accounts.filter { it.walletLinked }
        } else {
            val mapped = sources.firstOrNull { it.packageName == source } ?: return null
            accounts.filter { it.id in mapped.accountIds }
        }
        parsed.cardDigits?.let { digits ->
            candidates.firstOrNull { it.cardLastFour == digits || it.identity?.endsWith(digits) == true }
                ?.let { return it.id }
        }
        candidates.singleOrNull()?.let { return it.id }
        if (source != GOOGLE_WALLET) {
            sources.firstOrNull { it.packageName == source }?.defaultAccountId
                ?.takeIf { id -> candidates.any { it.id == id } }
                ?.let { return it }
        }
        return null
    }

    /**
     * The spend this notification repeats, if any.
     *
     * Two apps announcing one purchase - Wallet at the till, the bank a little later - are one
     * spend: the same amount within half an hour, and the same card when both show one. When both
     * show the same card's digits, within three hours: NatWest's app once told of a lunch 53
     * minutes after Wallet, and it was counted twice. One app
     * announcing the same amount twice is two purchases, unless it is the same notification
     * posted again within a minute; ten minutes either way both double-counted a slow bank and
     * merged two coffees.
     */
    fun duplicateOf(
        source: String,
        amountMinor: Long,
        cardDigits: String?,
        seenAt: Long,
        recent: List<SeenSpendEntity>,
    ): SeenSpendEntity? =
        recent.firstOrNull {
            if (it.dismissed || it.amountMinor != -amountMinor) return@firstOrNull false
            val apart = abs(it.seenAt - seenAt)
            if (it.source == source) {
                apart <= REPOST_WINDOW_MS
            } else {
                if (cardDigits != null && it.cardDigits != null) {
                    cardDigits == it.cardDigits && apart <= SAME_CARD_WINDOW_MS
                } else {
                    apart <= CROSS_APP_WINDOW_MS
                }
            }
        }

    /**
     * Pairs seen spends with the bank's rows that report them: same account, same amount, the
     * bank's date from the day before to five days after. One to one, oldest first, so two coffees
     * of the same price are two coffees.
     */
    fun matches(
        seen: List<SeenSpendEntity>,
        bank: List<TransactionEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        /** The other accounts a spend may turn out to be on: those its app covers. */
        alternatives: (SeenSpendEntity) -> Set<String> = { emptySet() },
    ): Map<String, TransactionEntity> {
        val claimed = seen.mapNotNullTo(HashSet()) { it.matchedTransactionId }
        val result = linkedMapOf<String, TransactionEntity>()
        seen.filter { it.matchedTransactionId == null && !it.dismissed && it.countedOn != null }
            .sortedBy { it.seenAt }
            .forEach { spend ->
                val day = dateOf(spend.seenAt, zone)
                // The bank's word wins: a payment guessed, waiting on an answer, or even answered
                // wrongly goes to whichever account the bank reports it on - unless the card's own
                // digits said where it was. The account it's counted on is looked at first, so two
                // same-priced payments pair up right.
                val elsewhere = if (spend.cardDigits != null && !spend.accountGuessed && spend.accountId != null) emptySet() else alternatives(spend)
                val row = bank
                    .filter { tx ->
                        (tx.accountId == spend.countedOn || tx.accountId in elsewhere) &&
                            tx.amountMinor == spend.amountMinor &&
                            tx.transactionId !in claimed && !tx.transactionId.startsWith(SEEN_PREFIX) &&
                            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let {
                                !it.isBefore(day.minusDays(1)) && !it.isAfter(day.plusDays(HANDOVER_DAYS))
                            } == true
                    }
                    .minWithOrNull(compareBy({ it.accountId != spend.countedOn }, { it.bookingDate }))
                    ?: return@forEach
                claimed += row.transactionId
                result[spend.id] = row
            }
        return result
    }

    /**
     * Seen spends still counted: not yet reported by the bank, not dismissed, and with an
     * account - the one it's in, or while the user is asked, the likeliest.
     */
    fun counted(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        seen.filter { it.countedOn != null && it.matchedTransactionId == null && !it.dismissed }

    /** Waiting on the user to say which account. */
    fun unanswered(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        seen.filter { it.accountId == null && it.matchedTransactionId == null && !it.dismissed }

    /** Counted for a week without the bank reporting them: worth asking about. */
    fun unconfirmed(seen: List<SeenSpendEntity>, now: Long): List<SeenSpendEntity> =
        counted(seen).filter { it.accountId != null && !it.keptByUser && now - it.seenAt > REVIEW_AFTER_MS }

    /** A seen spend as the pending row the budget reads. */
    fun asTransaction(spend: SeenSpendEntity, sourceLabel: String, zone: ZoneId = ZoneId.systemDefault()) =
        TransactionEntity(
            accountId = spend.countedOn!!,
            transactionId = SEEN_PREFIX + spend.id,
            bookingDate = dateOf(spend.seenAt, zone).toString(),
            valueDate = null,
            amountMinor = spend.amountMinor,
            currency = spend.currency,
            payee = spend.merchant,
            description = "Seen · $sourceLabel · " +
                Instant.ofEpochMilli(spend.seenAt).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            isPending = true,
            rawJson = null,
            categoryOverride = spend.categoryOverride,
        )

    fun dateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    const val SEEN_PREFIX = "seen:"
    private const val CROSS_APP_WINDOW_MS = 30 * 60_000L
    private const val SAME_CARD_WINDOW_MS = 3 * 60 * 60_000L
    private const val REPOST_WINDOW_MS = 60_000L
    private const val HANDOVER_DAYS = 5L
    const val REVIEW_AFTER_MS = 7 * 24 * 60 * 60_000L
}

/** The account a seen spend is counted on: its own, or while it waits on an answer, the likeliest. */
val SeenSpendEntity.countedOn: String? get() = accountId ?: provisionalAccountId
