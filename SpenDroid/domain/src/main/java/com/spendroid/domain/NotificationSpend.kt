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
    fun repeats(seen: List<SeenSpendEntity>, setup: Setup = Setup.NONE): List<SeenSpendEntity> =
        repeatPairs(seen, setup).map { it.second }

    /** Each repeat with the earlier announcement it repeats. */
    fun repeatPairs(seen: List<SeenSpendEntity>, setup: Setup = Setup.NONE): List<Pair<SeenSpendEntity, SeenSpendEntity>> {
        // Pairings already made - a repeat stored as such - still hold: a Wallet payment with its
        // bank alert has its partner, and can't take another.
        val pairings = seen.filter { it.pairedWithId != null }.toMutableList()
        val kept = mutableListOf<SeenSpendEntity>()
        val repeats = mutableListOf<Pair<SeenSpendEntity, SeenSpendEntity>>()
        seen.filter { !it.dismissed }.sortedBy { it.seenAt }.forEach { spend ->
            val first = duplicateOf(spend.source, -spend.amountMinor, spend.cardDigits, spend.seenAt, kept + pairings, setup)
            if (first != null) {
                repeats += first to spend
                pairings += spend.copy(dismissed = true, pairedWithId = first.id)
            } else {
                kept += spend
            }
        }
        return repeats
    }

    /**
     * The announcements to drop as repeats of one purchase, each with the one it repeats: the
     * later one - unless the bank has already taken that one over, and then the earlier, still
     * counted on its own. One the bank reported is never dropped.
     */
    fun toDrop(seen: List<SeenSpendEntity>, setup: Setup = Setup.NONE): List<Pair<SeenSpendEntity, String>> =
        repeatPairs(seen, setup).mapNotNull { (first, repeat) ->
            when {
                repeat.matchedTransactionId == null -> repeat to first.id
                first.matchedTransactionId == null -> first to repeat.id
                else -> null
            }
        }.distinctBy { it.first.id }

    /** What [duplicateOf] needs to know which accounts an announcement could be on. */
    data class Setup(val sources: List<Source>, val accounts: List<AccountEntity>) {
        companion object {
            val NONE = Setup(emptyList(), emptyList())
        }
    }

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

    private val POUNDS = Regex(AMOUNT_GROUP)
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
                else -> {
                    // Whole characters: an emoji - NatWest's 💳 - is two halves, and quoted apart
                    // they match nothing, so a wording taught from it never matched again.
                    val end = i + Character.charCount(text.codePointAt(i))
                    out.append(Regex.escape(text.substring(i, end)))
                    i = end
                    continue
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * A wording taught before 0.6.2 with an emoji in it, its two halves quoted apart: put back
     * together, so it matches as it was meant to.
     */
    fun repairPattern(pattern: String): String =
        pattern.replace(Regex("""([\x{D800}-\x{DBFF}])\\E\\Q([\x{DC00}-\x{DFFF}])"""), "$1$2")

    // At most twelve digits of pounds - a promotion's "£1000000000000000000000!" can't overflow
    // the sum and throw; it isn't a payment, so it simply doesn't read as one.
    private const val AMOUNT_GROUP = """(?:£|GBP\s?)(\d{1,3}(?:,\d{3}){1,3}|\d{1,12})(?!\d)(?:\.(\d{2}))?"""

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
     * Google Wallet and a banking app announcing one purchase are one spend. Wallet tells of
     * every payment it makes, and the card's banking app - if it's one read - tells of it too,
     * in either order: Wallet straight away, or once the phone has signal again; the bank when
     * it approves the payment, which once was 53 minutes after Wallet. So each Wallet
     * announcement takes one partner from a banking app, and each banking-app one a partner from
     * Wallet: the same amount, within a day, on an account both could be - and not a card whose
     * digits say otherwise. One already partnered takes no other, so a second round paid with the
     * plastic card is a second spend.
     *
     * Two banking apps announcing the same amount are one spend within half an hour, with the
     * same card when both show one. One app announcing the same amount twice is two purchases,
     * unless it is the same notification posted again within a minute; ten minutes either way
     * both double-counted a slow bank and merged two coffees.
     *
     * [recent] may hold repeats stored as such ([SeenSpendEntity.pairedWithId]): they are never
     * the answer, but they say who already has a partner.
     */
    fun duplicateOf(
        source: String,
        amountMinor: Long,
        cardDigits: String?,
        seenAt: Long,
        recent: List<SeenSpendEntity>,
        setup: Setup = Setup.NONE,
    ): SeenSpendEntity? {
        // Only a Wallet-and-banking-app pairing uses up a partner; a repost of the same
        // notification doesn't.
        val byId = recent.associateBy { it.id }
        val partnered = HashSet<String>()
        recent.forEach { r ->
            val p = r.pairedWithId?.let(byId::get) ?: return@forEach
            if ((r.source == GOOGLE_WALLET) != (p.source == GOOGLE_WALLET)) {
                partnered += r.id
                partnered += p.id
            }
        }
        val mine = couldBe(source, cardDigits, null, setup)
        val wallet = source == GOOGLE_WALLET
        return recent.firstOrNull {
            if (it.dismissed || it.amountMinor != -amountMinor) return@firstOrNull false
            val apart = abs(it.seenAt - seenAt)
            val sameCard = cardDigits == null || it.cardDigits == null || cardDigits == it.cardDigits
            when {
                it.source == source -> apart <= REPOST_WINDOW_MS
                wallet != (it.source == GOOGLE_WALLET) ->
                    it.id !in partnered && apart <= PARTNER_WINDOW_MS && sameCard &&
                        overlap(mine, couldBe(it.source, it.cardDigits, it, setup))
                else -> apart <= CROSS_APP_WINDOW_MS && sameCard
            }
        }
    }

    /**
     * The accounts an announcement could be on: the one it was put on for certain, else those
     * its app covers - narrowed by the card's digits where they pick one out. Empty when the
     * set-up isn't known, which rules nothing out.
     */
    private fun couldBe(source: String, digits: String?, stored: SeenSpendEntity?, setup: Setup): Set<String> {
        if (stored?.accountId != null && !stored.accountGuessed) return setOf(stored.accountId)
        if (setup.accounts.isEmpty()) return emptySet()
        val candidates = candidatesFor(source, setup.sources, setup.accounts).orEmpty()
        val byDigits = digits?.let { d -> candidates.filter { it.cardLastFour == d || it.identity?.endsWith(d) == true } }.orEmpty()
        return byDigits.ifEmpty { candidates }.mapTo(HashSet()) { it.id }
    }

    private fun overlap(a: Set<String>, b: Set<String>) = a.isEmpty() || b.isEmpty() || a.any { it in b }

    /**
     * Whether a bank row dated [date] can report a purchase seen at [seenAt]: dated that day or
     * after - or the day before, if seen in the small hours (a purchase just before midnight) or
     * the row is still pending (a bank that announces a purchase hours late, while its row is
     * new). Not a settled row from the day before: on 21 Oct a £9.38 lunch seen at 12:41 paired
     * with the same lunch's row from the 20th - Monday's, posted on Tuesday - and the new
     * purchase went uncounted.
     */
    fun mayReport(date: LocalDate, pending: Boolean, seenAt: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val at = Instant.ofEpochMilli(seenAt).atZone(zone)
        val day = at.toLocalDate()
        return when {
            !date.isBefore(day) -> !date.isAfter(day.plusDays(HANDOVER_DAYS))
            date == day.minusDays(1) -> at.hour < SMALL_HOURS_END || pending
            else -> false
        }
    }

    /**
     * Recent seen spends handed to a bank row the bank no longer lists - a pending row dropped
     * while it moves to booked, perhaps under a new id. Left paired, the purchase showed nowhere:
     * £17.24 at Waterstones, counted at 13:19, gone by 17:00. Unpaired, it's counted again, and
     * pairs with the bank's row once that's back. Only within the days a pairing can be made in,
     * or a long-booked purchase's old pending pairing would come back as a second one.
     */
    fun orphaned(seen: List<SeenSpendEntity>, bank: List<TransactionEntity>, now: Long): List<SeenSpendEntity> {
        val listed = bank.mapTo(HashSet()) { it.accountId to it.transactionId }
        return seen.filter { spend ->
            val id = spend.matchedTransactionId ?: return@filter false
            !spend.dismissed && now - spend.seenAt <= HANDOVER_DAYS * DAY_MS && (spend.accountId to id) !in listed
        }
    }

    /**
     * Seen spends handed to a bank row dated before they could be - paired before
     * [mayReport] was kept to - to be counted again until their own row comes.
     */
    fun misdated(seen: List<SeenSpendEntity>, bank: List<TransactionEntity>, zone: ZoneId = ZoneId.systemDefault()): List<SeenSpendEntity> {
        val rows = bank.associateBy { it.accountId to it.transactionId }
        return seen.filter { spend ->
            val id = spend.matchedTransactionId ?: return@filter false
            val row = rows[spend.accountId to id] ?: return@filter false
            RecurringAnalyzer.parseBookingDate(row.bookingDate)?.let { it.isBefore(dateOf(spend.seenAt, zone)) && !mayReport(it, row.isPending, spend.seenAt, zone) } == true
        }
    }

    /**
     * Pairs seen spends with the bank's rows that report them: same account, same amount, the
     * bank's date as [mayReport] allows, up to five days after. One to one, oldest first, so two coffees
     * of the same price are two coffees.
     */
    fun matches(
        seen: List<SeenSpendEntity>,
        bank: List<TransactionEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        /** The other accounts a spend may turn out to be on: those its app covers. */
        alternatives: (SeenSpendEntity) -> Set<String> = { emptySet() },
    ): Map<String, TransactionEntity> {
        // A row is taken by a spend matched to it - one seen about the same time. A claim from
        // weeks before is to a row long gone; a bank that reuses ids on pending rows would
        // otherwise leave every later purchase there unmatched, and counted twice.
        val claims = seen.filter { it.matchedTransactionId != null }
        val taken = HashSet<String>()
        fun claimed(tx: TransactionEntity, spend: SeenSpendEntity) = tx.transactionId in taken ||
            claims.any { it.matchedTransactionId == tx.transactionId && abs(it.seenAt - spend.seenAt) <= CLAIM_WINDOW_MS }
        val result = linkedMapOf<String, TransactionEntity>()
        seen.filter { it.matchedTransactionId == null && !it.dismissed && it.countedOn != null }
            .sortedBy { it.seenAt }
            .forEach { spend ->
                fun fits(tx: TransactionEntity) =
                    tx.amountMinor == spend.amountMinor &&
                        !tx.transactionId.startsWith(SEEN_PREFIX) && !claimed(tx, spend) &&
                        RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { mayReport(it, tx.isPending, spend.seenAt, zone) } == true
                // The bank's word wins: a payment guessed, waiting on an answer, or even answered
                // wrongly goes to whichever account the bank reports it on - unless the card's own
                // digits said where it was. The account it's counted on is looked at first, so two
                // same-priced payments pair up right.
                val certain = spend.cardDigits != null && !spend.accountGuessed && spend.accountId != null
                val elsewhere = if (certain) emptySet() else alternatives(spend)
                val row = bank
                    .filter { tx -> (tx.accountId == spend.countedOn || tx.accountId in elsewhere) && fits(tx) }
                    .minWithOrNull(compareBy({ it.accountId != spend.countedOn }, { it.bookingDate }))
                    // Else the shop's own name: the same amount, in the window, on any account,
                    // with a word of the name in common - "WAITROSE STORES" and "WAITROSE STORES
                    // 4168". Counted on an account the bank doesn't list it under, the purchase was
                    // otherwise counted twice: once seen, once from the bank. Not where the card's
                    // digits said for certain where it was.
                    ?: bank.takeUnless { certain }?.filter { tx -> fits(tx) && sharesWord(tx.payee, spend.merchant) }?.singleOrNull()
                    ?: return@forEach
                taken += row.transactionId
                result[spend.id] = row
            }
        return result
    }

    /**
     * Repeats the bank has ruled out: one app announcing the same payment twice minutes apart -
     * Google Wallet did, at 15:47 and 15:57, for one £63.50 shop - reads as two purchases until
     * the bank lists only one. Once the account has synced [SETTLE_AFTER_MS] after the later
     * one, a seen spend the bank hasn't reported, whose twin (same app, amount and card, within
     * [TWIN_WINDOW_MS]) the bank has, is a repeat. Wrong, it costs nothing: a real second
     * purchase arrives as the bank's own row and is counted from that.
     *
     * [lastSynced] by account, for a bank link. On a manual account the bank has had its say
     * once an imported statement covers the day ([coverage], as [ManualAccounts.covers]): it
     * lists the payment once, and the second announcement goes the same way.
     */
    fun repeatsRuledOut(
        seen: List<SeenSpendEntity>,
        lastSynced: Map<String, Long>,
        coverage: Map<String, LocalDate?> = emptyMap(),
    ): List<Pair<SeenSpendEntity, String>> {
        val matched = seen.filter { it.matchedTransactionId != null && !it.dismissed }
        val used = HashSet<String>()
        return seen
            .filter { it.matchedTransactionId == null && !it.dismissed && !it.keptByUser && !it.confirmedSeparate }
            .sortedBy { it.seenAt }
            .mapNotNull { spend ->
                val account = spend.countedOn ?: return@mapNotNull null
                val settled = if (account in coverage) {
                    ManualAccounts.covers(coverage[account], spend)
                } else {
                    lastSynced[account]?.let { it >= spend.seenAt + SETTLE_AFTER_MS } == true
                }
                if (!settled) return@mapNotNull null
                val twin = matched.firstOrNull { other ->
                    other.id !in used && other.source == spend.source && other.amountMinor == spend.amountMinor &&
                        abs(other.seenAt - spend.seenAt) <= TWIN_WINDOW_MS &&
                        (other.cardDigits == null || spend.cardDigits == null || other.cardDigits == spend.cardDigits)
                } ?: return@mapNotNull null
                used += twin.id
                spend to twin.id
            }
    }

    /** Two payments alike on one day: [spend], counted, may be [twin] announced again. */
    data class Repeat(val spend: SeenSpendEntity, val twin: SeenSpendEntity)

    /**
     * Payments Penny asks about: two seen the same day, for the same amount, at the same or a
     * similar shop, on the same card where both show one - two coffees, or one shop counted
     * twice. Not when the bank has listed both, nor once the user has said both were real. The
     * one asked about is one the bank hasn't listed, the later where neither is. Both stay
     * counted until answered: a cautious budget rather than a generous one.
     */
    fun possibleRepeats(seen: List<SeenSpendEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Repeat> {
        val live = seen.filter { !it.dismissed && it.countedOn != null }.sortedBy { it.seenAt }
        val used = HashSet<String>()
        val result = mutableListOf<Repeat>()
        for (i in live.indices) {
            for (j in i + 1 until live.size) {
                val a = live[i]
                val b = live[j]
                if (a.id in used || b.id in used) continue
                if (a.amountMinor != b.amountMinor || a.currency != b.currency) continue
                if (dateOf(a.seenAt, zone) != dateOf(b.seenAt, zone)) continue
                if (a.cardDigits != null && b.cardDigits != null && a.cardDigits != b.cardDigits) continue
                if (!sameShop(a.merchant, b.merchant)) continue
                val spend = when {
                    b.matchedTransactionId == null -> b
                    a.matchedTransactionId == null -> a
                    else -> continue
                }
                if (spend.confirmedSeparate || spend.keptByUser) continue
                used += a.id
                used += b.id
                result += Repeat(spend, if (spend === b) a else b)
            }
        }
        return result
    }

    /**
     * Penny's answer about a payment, for changing it: [counted] is the one that stands - the
     * seen spend shown, or the one the bank's row took over from - and [repeats] those counted
     * once with it (said "once", dropped as a repeat, or the same purchase from a second app);
     * [twin], when the user said both were real, the other of the two.
     */
    data class Answer(val counted: SeenSpendEntity, val repeats: List<SeenSpendEntity>, val twin: SeenSpendEntity?)

    fun answerFor(seenId: String?, bankRow: TransactionEntity?, seen: List<SeenSpendEntity>, zone: ZoneId = ZoneId.systemDefault()): Answer? {
        val counted = seenId?.let { id -> seen.firstOrNull { it.id == id } }
            ?: bankRow?.let { row -> seen.firstOrNull { it.matchedTransactionId == row.transactionId && it.accountId == row.accountId } }
            ?: return null
        val repeats = seen.filter { it.dismissed && it.pairedWithId == counted.id }
        val twin = if (counted.confirmedSeparate) {
            seen.firstOrNull { other ->
                other.id != counted.id && !other.dismissed && other.amountMinor == counted.amountMinor &&
                    dateOf(other.seenAt, zone) == dateOf(counted.seenAt, zone) && sameShop(other.merchant, counted.merchant)
            }
        } else {
            null
        }
        if (repeats.isEmpty() && twin == null) return null
        return Answer(counted, repeats, twin)
    }

    private fun sameShop(a: String, b: String): Boolean =
        a.isBlank() || b.isBlank() || a.trim().equals(b.trim(), ignoreCase = true) || sharesWord(a, b)

    /**
     * Why a seen spend has or hasn't been handed to a bank row, line by line, for the detail
     * sheet: how it's held, what [matches] would do with it now, and every bank row for the
     * same amount within ten days with the first reason it doesn't fit. Built on the same rules
     * as [matches], so it can't tell a different story from it.
     */
    fun explain(
        spend: SeenSpendEntity,
        seen: List<SeenSpendEntity>,
        bank: List<TransactionEntity>,
        alternatives: Set<String>,
        name: (String?) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val lines = mutableListOf<String>()
        val time = Instant.ofEpochMilli(spend.seenAt).atZone(zone)
        val day = time.toLocalDate()
        lines += "Seen ${time.format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))} · " +
            (spend.cardDigits?.let { "card ••$it" } ?: "no card digits")
        lines += "Counted on ${name(spend.countedOn)}" + when {
            spend.accountId == null -> " for now, waiting for you to say"
            spend.accountGuessed -> ", guessed"
            spend.cardDigits != null -> ", certain from the card digits"
            else -> ""
        }
        val certain = spend.cardDigits != null && !spend.accountGuessed && spend.accountId != null
        val elsewhere = if (certain) emptySet() else alternatives
        if (elsewhere.isNotEmpty()) lines += "Could also be on ${elsewhere.joinToString { name(it) }}"
        spend.matchedTransactionId?.let { lines += "Handed over to bank line …${it.takeLast(6)}" }
        if (spend.dismissed) lines += "Dismissed"

        val now = matches(seen, bank, zone) { if (it.id == spend.id) alternatives else emptySet() }[spend.id]
        lines += if (now != null) {
            "Would pair now with ${now.payee} on ${name(now.accountId)}, ${now.bookingDate} - it pairs at the next sync or refresh"
        } else {
            "Would not pair now"
        }

        val rows = bank.filter { tx ->
            tx.amountMinor == spend.amountMinor && !tx.transactionId.startsWith(SEEN_PREFIX) &&
                RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { abs(it.toEpochDay() - day.toEpochDay()) <= 10 } == true
        }
        if (rows.isEmpty()) {
            lines += "No bank line for this amount within ten days"
            return lines
        }
        rows.forEach { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate)!!
            val holder = seen.firstOrNull { it.id != spend.id && it.matchedTransactionId == tx.transactionId }
            val why = when {
                tx.accountId != spend.countedOn && tx.accountId !in elsewhere ->
                    if (sharesWord(tx.payee, spend.merchant) && !certain) "another account, but the shop's name fits" else "another account"
                date.isAfter(day.plusDays(HANDOVER_DAYS)) -> "dated too long after"
                !mayReport(date, tx.isPending, spend.seenAt, zone) -> "dated before the purchase"
                holder != null && abs(holder.seenAt - spend.seenAt) <= CLAIM_WINDOW_MS ->
                    "already taken by ${holder.merchant}, seen ${Instant.ofEpochMilli(holder.seenAt).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))}"
                else -> "fits"
            }
            lines += "${tx.payee} · ${name(tx.accountId)} · ${tx.bookingDate}" +
                (if (tx.isPending) " · pending" else "") + " · id …${tx.transactionId.takeLast(6)}: $why"
        }
        return lines
    }

    /**
     * Seen spends still counted: not yet reported by the bank, not dismissed, and with an
     * account - the one it's in, or while the user is asked, the likeliest.
     */
    fun counted(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        seen.filter { it.countedOn != null && it.matchedTransactionId == null && !it.dismissed }

    /**
     * Under this, a payment is more likely a card check - a penny or two taken to see the card
     * works, and given straight back - than something bought. Penny asks rather than assumes.
     */
    const val TINY_MINOR = 10L

    fun isTiny(spend: SeenSpendEntity): Boolean = -spend.amountMinor in 1 until TINY_MINOR

    /** Tiny payments not yet said to be real or a check, nor reported by the bank. */
    fun cardChecks(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        seen.filter { it.matchedTransactionId == null && !it.dismissed && !it.keptByUser && isTiny(it) }

    /** Waiting on the user to say which account. */
    fun unanswered(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        seen.filter { it.accountId == null && it.matchedTransactionId == null && !it.dismissed }

    /**
     * Worth asking about: counted for a week without the bank reporting them. On a manual
     * account - [coverage], by account, the last day a statement covers - there is no sync to
     * report them, so only once a statement covers the day and still doesn't list it.
     */
    fun unconfirmed(seen: List<SeenSpendEntity>, now: Long, coverage: Map<String, LocalDate?> = emptyMap()): List<SeenSpendEntity> =
        counted(seen).filter { spend ->
            spend.accountId != null && !spend.keptByUser &&
                if (spend.accountId in coverage) ManualAccounts.covers(coverage[spend.accountId], spend)
                else now - spend.seenAt > REVIEW_AFTER_MS
        }

    /**
     * Seen spends old enough to forget: past [before], except on a manual account no statement
     * covers yet - there, forgetting it would forget real spending.
     */
    fun forgettable(seen: List<SeenSpendEntity>, before: Long, coverage: Map<String, LocalDate?>): List<SeenSpendEntity> =
        seen.filter { spend ->
            spend.seenAt < before && !(
                spend.countedOn in coverage && spend.matchedTransactionId == null && !spend.dismissed &&
                    !ManualAccounts.covers(coverage[spend.countedOn], spend)
                )
        }

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

    /** A word of four letters or more in both names, other than the ones every shop has. */
    internal fun sharesWord(a: String, b: String): Boolean {
        fun words(x: String) = x.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in COMMON_WORDS }.toSet()
        return (words(a) intersect words(b)).isNotEmpty()
    }

    private val COMMON_WORDS = setOf("store", "stores", "shop", "shops", "limited", "payment", "card", "online", "superstore", "express", "local", "group", "services")

    const val SEEN_PREFIX = "seen:"
    private const val CLAIM_WINDOW_MS = 10 * 24 * 60 * 60_000L
    private const val TWIN_WINDOW_MS = 30 * 60_000L
    private const val SETTLE_AFTER_MS = 3 * 60 * 60_000L
    private const val CROSS_APP_WINDOW_MS = 30 * 60_000L
    private const val PARTNER_WINDOW_MS = 24 * 60 * 60_000L
    private const val REPOST_WINDOW_MS = 60_000L
    private const val HANDOVER_DAYS = 5L
    private const val DAY_MS = 24 * 60 * 60_000L
    /** Seen before this hour, a purchase may be dated the day before by the bank. */
    private const val SMALL_HOURS_END = 3
    const val REVIEW_AFTER_MS = 7 * 24 * 60 * 60_000L
}

/** The account a seen spend is counted on: its own, or while it waits on an answer, the likeliest. */
val SeenSpendEntity.countedOn: String? get() = accountId ?: provisionalAccountId
