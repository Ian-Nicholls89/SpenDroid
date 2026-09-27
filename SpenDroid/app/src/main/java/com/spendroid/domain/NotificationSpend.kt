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

    /** One bank app the user picked, and the accounts it speaks for. */
    data class Source(
        val packageName: String,
        val label: String,
        val accountIds: List<String>,
        val defaultAccountId: String? = null,
    )

    data class Parsed(
        /** Positive: what was spent. */
        val amountMinor: Long,
        val merchant: String,
        val cardDigits: String?,
    )

    /** Money coming in, refunds, refusals and balance news: never a spend. */
    private val NOT_A_SPEND = Regex(
        """\b(declined|refund(ed)?|received|credited|paid you|sent you|incoming|balance|failed|reversed|""" +
            """cancel(l)?ed|top.?up|salary|deposit(ed)?|payment due|statement|interest|reminder|limit)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Wording a bank uses for a card payment. A bank notification without any is not read. */
    private val SPEND_WORDS = Regex(
        """\b(spent|spend|payment|paid|purchase|card|transaction|debit|contactless)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val POUNDS = Regex("""(?:£|GBP\s?)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?""")
    private val OTHER_CURRENCY = Regex("""[€$¥]|\b(EUR|USD)\b""")
    private val CARD_DIGITS = Regex("""(?:[•*·xX]{2,}|ending(?: in)?)\s?(\d{4})\b""")
    private val AT_MERCHANT = Regex(
        """\b(?:at|to)\s+([A-Za-z0-9][^.,\n£]{0,40}?)(?=\s+(?:on|using|with|for|from|via)\b|[.,\n]|$)""",
    )

    fun parse(source: String, title: String?, text: String?): Parsed? {
        val body = listOfNotNull(title, text).joinToString("\n").trim()
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
     * Which account a payment was made from: the card's digits where the notification shows
     * them; else, for Wallet, the only account marked as being in it; for a bank app, the only
     * account it speaks for, or the one the user made its default. Null when it cannot be told -
     * then the user is asked, and nothing is counted until they answer.
     */
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

    /** Wallet and the bank both announcing one purchase: same amount within ten minutes. */
    fun duplicateOf(amountMinor: Long, seenAt: Long, recent: List<SeenSpendEntity>): SeenSpendEntity? =
        recent.firstOrNull {
            !it.dismissed && it.amountMinor == -amountMinor && abs(it.seenAt - seenAt) <= DUPLICATE_WINDOW_MS
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
    ): Map<String, TransactionEntity> {
        val claimed = seen.mapNotNullTo(HashSet()) { it.matchedTransactionId }
        val result = linkedMapOf<String, TransactionEntity>()
        seen.filter { it.matchedTransactionId == null && !it.dismissed && it.accountId != null }
            .sortedBy { it.seenAt }
            .forEach { spend ->
                val day = dateOf(spend.seenAt, zone)
                val row = bank
                    .filter { tx ->
                        tx.accountId == spend.accountId && tx.amountMinor == spend.amountMinor &&
                            tx.transactionId !in claimed && !tx.transactionId.startsWith(SEEN_PREFIX) &&
                            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let {
                                !it.isBefore(day.minusDays(1)) && !it.isAfter(day.plusDays(HANDOVER_DAYS))
                            } == true
                    }
                    .minByOrNull { it.bookingDate }
                    ?: return@forEach
                claimed += row.transactionId
                result[spend.id] = row
            }
        return result
    }

    /** Seen spends still counted: known account, not yet reported by the bank, not dismissed. */
    fun counted(seen: List<SeenSpendEntity>): List<SeenSpendEntity> =
        seen.filter { it.accountId != null && it.matchedTransactionId == null && !it.dismissed }

    /** Counted for a week without the bank reporting them: worth asking about. */
    fun unconfirmed(seen: List<SeenSpendEntity>, now: Long): List<SeenSpendEntity> =
        counted(seen).filter { !it.keptByUser && now - it.seenAt > REVIEW_AFTER_MS }

    /** A seen spend as the pending row the budget reads. */
    fun asTransaction(spend: SeenSpendEntity, sourceLabel: String, zone: ZoneId = ZoneId.systemDefault()) =
        TransactionEntity(
            accountId = spend.accountId!!,
            transactionId = SEEN_PREFIX + spend.id,
            bookingDate = dateOf(spend.seenAt, zone).toString(),
            valueDate = null,
            amountMinor = spend.amountMinor,
            currency = spend.currency,
            payee = spend.merchant,
            description = "Seen · $sourceLabel",
            isPending = true,
            rawJson = null,
            categoryOverride = spend.categoryOverride,
        )

    fun dateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    const val SEEN_PREFIX = "seen:"
    private const val DUPLICATE_WINDOW_MS = 10 * 60_000L
    private const val HANDOVER_DAYS = 5L
    const val REVIEW_AFTER_MS = 7 * 24 * 60 * 60_000L
}
