package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.NotificationSampleEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.domain.CardDigits
import com.spendroid.domain.ManualAccounts
import com.spendroid.domain.NotificationSpend
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.toRecurringRule
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Spending read from notifications: the bank apps and Wallet watched, each payment they announce
 * counted - once, on the right account - until the bank's own row takes it over, and the user's
 * answers about them. One notification, answer or reconcile at a time, under [notificationLock].
 */
class SeenSpendStore(
    private val dao: com.spendroid.data.db.BudgetDao,
    private val secrets: SecretsStore,
) {


    val spendSources: Flow<List<NotificationSpend.Source>> = secrets.spendSources
    val spendReadingOn: Flow<Boolean> = secrets.spendReadingOn
    val spendLearned: Flow<List<NotificationSpend.Learned>> = secrets.spendLearned
    val seenSpends: Flow<List<SeenSpendEntity>> = dao.seenSpendsFlow()
    val notificationSamples: Flow<List<NotificationSampleEntity>> = dao.notificationSamplesFlow()

    suspend fun saveSpendSources(sources: List<NotificationSpend.Source>) {
        secrets.saveSpendSources(sources)
        watched = null
    }
    suspend fun setSpendReadingOn(on: Boolean) = secrets.setSpendReadingOn(on)

    /**
     * Packages whose notifications are read: the bank apps picked, and Wallet if any card is in
     * it. Asked for every notification the phone shows - every message, every app - so it is
     * kept rather than read from settings and the database each time, and dropped when either
     * of the things it comes from changes.
     */
    suspend fun watchedPackages(): Set<String> {
        watched?.let { return it }
        val apps = spendSources.first().map { it.packageName }.toSet()
        val wallet = dao.accounts().any { it.walletLinked }
        return (if (wallet) apps + NotificationSpend.GOOGLE_WALLET else apps).also { watched = it }
    }

    @Volatile private var watched: Set<String>? = null

    /**
     * A notification from a watched app: kept for thirty days as a sample, and when it reads as a
     * card payment, counted - unless the same purchase was already seen, or the bank has already
     * reported it. Returns true when the figures changed.
     */
    /**
     * One notification at a time. Wallet and the bank announce a purchase within the same
     * second, and handled side by side each checked for a duplicate before the others were
     * saved - so one lunch could count three times.
     */
    private val notificationLock = kotlinx.coroutines.sync.Mutex()

    /**
     * A payment read from a notification that waits on the user to say which account, with the
     * accounts it could be: for asking from a notification of SpenDroid's own.
     */
    suspend fun questionFor(source: String, postedAt: Long): Pair<SeenSpendEntity, List<AccountEntity>>? {
        val spend = dao.seenSpends().firstOrNull { it.id == "$source|$postedAt" } ?: return null
        if (spend.accountId != null || spend.dismissed || spend.matchedTransactionId != null) return null
        val candidates = NotificationSpend.candidatesFor(source, spendSources.first(), dao.accounts()).orEmpty()
        return if (candidates.size < 2) null else spend to candidates
    }

    /** Whether "always put this shop here" is set for [spend], and where. */
    suspend fun placeOf(spend: SeenSpendEntity): String? = spendPlaces.first()[NotificationSpend.placeKey(spend.source, spend.merchant)]

    /** Set when a Wallet announcement settled which account an earlier bank alert was on. */
    @Volatile private var settledByPartner = false

    /**
     * Payments seen in the last day that no longer wait on an answer - so a "which account?"
     * still showing for one can go.
     */
    suspend fun settledSince(since: Long): List<String> =
        dao.seenSpends().filter { it.seenAt >= since && (it.accountId != null || it.dismissed || it.matchedTransactionId != null) }.map { it.id }

    suspend fun onNotification(source: String, title: String?, text: String?, postedAt: Long): Boolean =
        notificationLock.withLock {
            val parsed = NotificationSpend.parse(source, title, text, spendLearned.first())
            val sampleId = dao.insertNotificationSample(
                NotificationSampleEntity(source = source, postedAt = postedAt, title = title, text = text, parsed = parsed != null),
            )
            dao.deleteNotificationSamplesBefore(postedAt - SAMPLE_DAYS_MS)
            // A manual account's payments wait for the statement that lists them, however long.
            NotificationSpend.forgettable(dao.seenSpends(), postedAt - SEEN_SPEND_DAYS_MS, manualCoverage())
                .takeIf { it.isNotEmpty() }?.let { old -> dao.deleteSeenSpends(old.map { it.id }) }
            val outcome = parsed?.let { countSeen(source, it, postedAt) } ?: NotificationSpend.Outcome.NOT_A_PAYMENT
            dao.setNotificationOutcome(sampleId, outcome.name)
            val settled = settledByPartner.also { settledByPartner = false }
            outcome == NotificationSpend.Outcome.COUNTED || settled
        }

    /**
     * Counts a payment read from a notification - unless it repeats one already seen, or the
     * budget already accounts for it as a transfer or a regular bill.
     */
    private suspend fun countSeen(source: String, parsed: NotificationSpend.Parsed, postedAt: Long): NotificationSpend.Outcome {
        if (!spendReadingOn.first()) return NotificationSpend.Outcome.PAUSED
        val recent = dao.seenSpends()
        val accounts = dao.accounts()
        val setup = NotificationSpend.Setup(spendSources.first(), accounts)
        NotificationSpend.duplicateOf(source, parsed.amountMinor, parsed.cardDigits, postedAt, recent, setup)?.let { first ->
            // The banking app's alert came first and couldn't say which account - first direct
            // never does - but Wallet's shows the card: that settles it.
            if (first.accountId == null || first.accountGuessed) {
                NotificationSpend.placementFor(source, parsed, setup.sources, accounts, emptyList())
                    ?.takeIf { it.accountId != null && !it.guessed }
                    ?.takeIf { placed -> NotificationSpend.candidatesFor(first.source, setup.sources, accounts).orEmpty().any { it.id == placed.accountId } }
                    ?.let { placed ->
                        dao.upsertSeenSpend(first.copy(accountId = placed.accountId, accountGuessed = false, provisionalAccountId = null))
                        settledByPartner = true
                    }
            }
            // Kept on record, not counted: it marks the first as having its partner.
            dao.upsertSeenSpend(
                SeenSpendEntity(
                    id = "$source|$postedAt", source = source, seenAt = postedAt, amountMinor = -parsed.amountMinor,
                    currency = first.currency, merchant = parsed.merchant, cardDigits = parsed.cardDigits,
                    dismissed = true, pairedWithId = first.id,
                ),
            )
            return NotificationSpend.Outcome.DUPLICATE
        }
        val history = accounts.flatMap { dao.transactionsFor(it.id) }
        val rules = RecurringAnalyzer.analyze(history) + manualRules.first().mapNotNull { it.toRecurringRule() }
        if (NotificationSpend.alreadyAccountedFor(parsed, history, rules)) return NotificationSpend.Outcome.ACCOUNTED_FOR
        // What the user said before counts as much as what the bank did: their answers teach.
        val answered = dao.seenSpends().filter { it.accountId != null && !it.accountGuessed && !it.dismissed && it.matchedTransactionId == null }
            .map { NotificationSpend.asTransaction(it, "") }
        val placement = NotificationSpend.placementFor(source, parsed, spendSources.first(), accounts, history + answered, spendPlaces.first())
        val accountId = placement?.accountId
        dao.upsertSeenSpend(
            SeenSpendEntity(
                id = "$source|$postedAt",
                source = source,
                seenAt = postedAt,
                amountMinor = -parsed.amountMinor,
                currency = accounts.firstOrNull { it.id == (accountId ?: placement?.provisionalAccountId) }?.currency ?: "GBP",
                // A bank app's "GREGGS 4168" is Greggs, as its pending row will be.
                merchant = CardDigits.trim(parsed.merchant, cardDigits() + listOfNotNull(parsed.cardDigits)),
                cardDigits = parsed.cardDigits,
                accountId = accountId,
                accountGuessed = placement?.guessed == true,
                provisionalAccountId = placement?.provisionalAccountId,
            ),
        )
        reconcileSeenSpendsHeld()
        return NotificationSpend.Outcome.COUNTED
    }

    /**
     * Learns from one of the notifications seen: count ones worded like it, or ignore them. A
     * "count" also counts that notification now, as it would have been had the app known.
     * Returns false when there is nothing in it to learn around - no single amount.
     */
    suspend fun teachNotification(sampleId: Long, count: Boolean): Boolean {
        val sample = dao.notificationSamples().firstOrNull { it.id == sampleId } ?: return false
        val learned = NotificationSpend.learn(sample.source, sample.title, sample.text, count) ?: return false
        secrets.updateSpendLearned { current -> current.filterNot { it.source == learned.source && it.pattern == learned.pattern } + learned }
        dao.markNotificationSampleParsed(sampleId, count)
        if (count) {
            val outcome = notificationLock.withLock {
                NotificationSpend.parse(sample.source, sample.title, sample.text, listOf(learned))
                    ?.let { countSeen(sample.source, it, sample.postedAt) }
            }
            outcome?.let { dao.setNotificationOutcome(sampleId, it.name) }
        } else {
            dao.setNotificationOutcome(sampleId, NotificationSpend.Outcome.NOT_A_PAYMENT.name)
            // Anything already counted from notifications worded like it goes.
            val seen = dao.seenSpends().filter { it.source == sample.source && it.matchedTransactionId == null && !it.dismissed }
            val ignored = dao.notificationSamples()
                .filter { it.source == sample.source && Regex(learned.pattern).containsMatchIn(NotificationSpend.bodyOf(it.title, it.text)) }
                .map { it.postedAt }
                .toSet()
            notificationLock.withLock {
                dao.seenSpends().filter { s -> s.id in seen.map { it.id }.toSet() && s.seenAt in ignored }.forEach { dao.upsertSeenSpend(it.copy(dismissed = true)) }
            }
        }
        return true
    }

    suspend fun forgetLearned() = secrets.updateSpendLearned { emptyList() }

    /**
     * Hands each seen spend over to the bank's row once one reports it, carrying a category set
     * on the seen spend across. Run after every sync and every new notification.
     */
    suspend fun reconcileSeenSpends() = notificationLock.withLock { reconcileSeenSpendsHeld() }

    /**
     * [reconcileSeenSpends] with [notificationLock] already held: every change to a seen spend
     * goes through the lock, or a sync's reconcile - reading them all, then writing back copies
     * - could put back a row a notification or an answer had just changed.
     */
    private suspend fun reconcileSeenSpendsHeld() {
        // Any purchase counted more than once - as the race before 3.2.1 could, or a bank app
        // announcing it long after Wallet - is one again. Ones the bank already reported are
        // looked at too, so the twin of a purchase the bank took over goes as well; only
        // ones not yet reported are ever dropped.
        NotificationSpend.toDrop(dao.seenSpends(), NotificationSpend.Setup(spendSources.first(), dao.accounts()))
            .forEach { (spend, partner) -> dao.upsertSeenSpend(spend.copy(dismissed = true, pairedWithId = partner)) }
        if (dao.seenSpends().isEmpty()) return
        val bank = dao.accounts().flatMap { dao.transactionsFor(it.id) }
        // Paired with a row dated before the purchase, as could happen before 5.5.3: counted
        // again until the bank lists the purchase itself. Once only - after that, a pairing with
        // a pending row the bank has since settled looks the same, and is right.
        // Paired with a bank row that's no longer listed: counted again until the bank's row is back.
        NotificationSpend.orphaned(dao.seenSpends(), bank, System.currentTimeMillis()).forEach { dao.upsertSeenSpend(it.copy(matchedTransactionId = null)) }
        if (!secrets.misdatedRepaired.first()) {
            NotificationSpend.misdated(dao.seenSpends(), bank).forEach { dao.upsertSeenSpend(it.copy(matchedTransactionId = null)) }
            secrets.setMisdatedRepaired()
        }
        val seen = dao.seenSpends()
        val sources = spendSources.first().associateBy { it.packageName }
        val walletAccounts = dao.accounts().filter { it.walletLinked }.map { it.id }.toSet()
        val alternatives = { spend: SeenSpendEntity ->
            if (spend.source == NotificationSpend.GOOGLE_WALLET) walletAccounts
            else sources[spend.source]?.accountIds?.toSet().orEmpty()
        }
        NotificationSpend.matches(seen, bank, alternatives = alternatives).forEach { (id, row) ->
            val spend = seen.first { it.id == id }
            // A guess the bank has now settled: the account is whichever it reported it on.
            dao.upsertSeenSpend(spend.copy(matchedTransactionId = row.transactionId, accountId = row.accountId, accountGuessed = false))
            if (spend.categoryOverride != null && row.categoryOverride == null) {
                dao.setCategoryOverride(row.accountId, row.transactionId, spend.categoryOverride)
            }
        }
        // Announced twice, listed once by the bank: the second announcement was a repeat.
        val synced = dao.accounts().filter { !it.manual && it.lastSynced > 0L }.associate { it.id to it.lastSynced }
        NotificationSpend.repeatsRuledOut(dao.seenSpends(), synced, manualCoverage()).forEach { (spend, twin) ->
            dao.upsertSeenSpend(spend.copy(dismissed = true, pairedWithId = twin))
        }
    }

    val spendPlaces: Flow<Map<String, String>> = secrets.spendPlaces

    /**
     * The user's answer to "which account?", or a change of mind about one already placed.
     * Remembers the card's digits for next time, and with [always], the shop's account.
     */
    suspend fun assignSeenSpend(id: String, accountId: String, always: Boolean = false) = notificationLock.withLock {
        val spend = dao.seenSpends().firstOrNull { it.id == id } ?: return@withLock
        dao.upsertSeenSpend(spend.copy(accountId = accountId, accountGuessed = false, provisionalAccountId = null))
        if (always) secrets.setSpendPlace(NotificationSpend.placeKey(spend.source, spend.merchant), accountId)
        val digits = spend.cardDigits
        val account = dao.accounts().firstOrNull { it.id == accountId }
        if (digits != null && account != null && account.cardLastFour == null) {
            dao.updateAccount(account.copy(cardLastFour = digits))
        }
        reconcileSeenSpendsHeld()
    }

    /** The question to ask now, if the payment just seen at [postedAt] may be one counted twice. */
    suspend fun repeatQuestionFor(postedAt: Long): NotificationSpend.Repeat? =
        NotificationSpend.possibleRepeats(dao.seenSpends()).firstOrNull { it.spend.seenAt == postedAt || it.twin.seenAt == postedAt }

    /** The user's answer to "twice, or once?": [once] drops [spendId] as a repeat of [twinId]. */
    suspend fun answerRepeat(spendId: String, twinId: String, once: Boolean) = notificationLock.withLock {
        val spend = dao.seenSpends().firstOrNull { it.id == spendId } ?: return@withLock
        // Either way round, so a wrong answer can be put right: once drops it as a repeat; twice
        // brings it back if it was dropped, and keeps it from being dropped again.
        dao.upsertSeenSpend(
            if (once) spend.copy(dismissed = true, pairedWithId = twinId, confirmedSeparate = false)
            else spend.copy(dismissed = false, pairedWithId = null, confirmedSeparate = true),
        )
    }

    suspend fun dismissSeenSpend(id: String) = notificationLock.withLock {
        dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(dismissed = true)) }
    }

    /** "A card check, or a real payment?": a check isn't counted; a real one is, reported or not. Either way round. */
    suspend fun answerCardCheck(id: String, check: Boolean) = notificationLock.withLock {
        dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(dismissed = check, keptByUser = !check)) }
    }

    suspend fun keepSeenSpend(id: String) = notificationLock.withLock {
        dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(keptByUser = true)) }
    }

    suspend fun clearNotificationData() = notificationLock.withLock {
        dao.deleteAllSeenSpends()
        dao.deleteAllNotificationSamples()
    }


    /** Spends in the database's own table get a category here until the bank reports them. */
    suspend fun setCategory(id: String, category: String?) = notificationLock.withLock {
        dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(categoryOverride = category)) }
    }

    /** [block] under [notificationLock]: for a write to seen spends made outside this store. */
    suspend fun <T> locked(block: suspend () -> T): T = notificationLock.withLock { block() }

    /** Dropped when the bank apps picked, or the cards in Wallet, change. */
    fun forgetWatched() {
        watched = null
    }

    /** Card digits the app knows: from accounts, and from payments seen. */
    suspend fun cardDigits(): Set<String> =
        CardDigits.known(dao.accounts().map { it.cardLastFour }, dao.seenSpends().map { it.cardDigits })

    /** For each manual account, the last day an imported statement covers. */
    suspend fun manualCoverage(): Map<String, LocalDate?> {
        val manual = dao.accounts().filter { it.manual }
        return ManualAccounts.coverage(manual, manual.flatMap { dao.transactionsFor(it.id) })
    }

    private val manualRules = dao.activeManualRulesFlow()

    private companion object {
        const val SAMPLE_DAYS_MS = 30L * 24 * 60 * 60_000L
        // Long after the bank has reported them; kept until then only so a match can be made.
        const val SEEN_SPEND_DAYS_MS = 60L * 24 * 60 * 60_000L
    }
}
