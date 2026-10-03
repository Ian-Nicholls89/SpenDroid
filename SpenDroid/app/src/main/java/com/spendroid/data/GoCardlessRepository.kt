package com.spendroid.data

import android.content.Context
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetDao
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.RuleOverrideEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.NotificationSampleEntity
import com.spendroid.domain.DuplicateCheck
import com.spendroid.domain.NotificationSpend
import com.spendroid.data.remote.AccountDetailsDto
import com.spendroid.data.remote.AccountInfoDto
import com.spendroid.data.remote.AccountInfoWrapperDto
import com.spendroid.data.remote.AmountDto
import com.spendroid.data.remote.BalancesDto
import com.spendroid.data.remote.GcAuthApi
import com.spendroid.data.remote.GcDataApi
import com.spendroid.data.remote.InstitutionDto
import com.spendroid.data.remote.RequisitionDto
import com.spendroid.data.remote.RequisitionRequestDto
import com.spendroid.data.remote.TokenManager
import com.spendroid.data.remote.TransactionDto
import com.spendroid.data.remote.TransactionsDto
import com.spendroid.domain.PayPalEngine
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.BudgetDay
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CardTiming
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.WorkingDayCalendar
import com.spendroid.domain.toRecurringRule
import com.google.gson.Gson
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GoCardlessRepository private constructor(
    private val secrets: SecretsStore,
    private val tokenManager: TokenManager,
    private val dataApi: GcDataApi,
    private val dao: BudgetDao,
) {

    val secretId: Flow<String?> = secrets.secretId
    val secretKey: Flow<String?> = secrets.secretKey

    /** See [SecretsStore.sealLegacySecrets]. */
    suspend fun sealLegacySecrets() = secrets.sealLegacySecrets()

    val connections: Flow<List<Connection>> = secrets.connections
    val ignoredRules: Flow<Set<String>> = secrets.ignoredRules
    val notifiedGoalWarnings: Flow<Set<String>> = secrets.notifiedGoalWarnings
    val notifiedLargeTransactions: Flow<Set<String>> = secrets.notifiedLargeTransactions
    val notifiedCardWarnings: Flow<Set<String>> = secrets.notifiedCardWarnings
    val notifiedDuplicates: Flow<Set<String>> = secrets.notifiedDuplicates

    suspend fun saveNotifiedDuplicates(keys: Set<String>) = secrets.saveNotifiedDuplicates(keys)
    val transferGroups: Flow<Set<String>> = secrets.transferGroups

    /** Marks or unmarks every payment like this one as a transfer, and re-applies at once. */
    suspend fun setTransferGroup(key: String, isTransfer: Boolean) {
        secrets.setTransferGroup(key, isTransfer)
        analyzeTransactions()
    }
    val manualRules: Flow<List<ManualRecurringRuleEntity>> = dao.activeManualRulesFlow()
    val notificationTime: Flow<String> = secrets.notificationTime
    val lastNotifiedVersionCode: Flow<Int> = secrets.lastNotifiedVersionCode
    val categoryRules: Flow<List<CategoryRuleEntity>> = dao.categoryRulesFlow()
    val budgetGoals: Flow<List<BudgetGoalEntity>> = dao.budgetGoalsFlow()
    val ruleOverrides: Flow<List<RuleOverrideEntity>> = dao.ruleOverridesFlow()
    val primaryIncomeKey: Flow<String?> = secrets.primaryIncomeKey

    suspend fun saveRuleOverride(override: RuleOverrideEntity) {
        if (override.anchorDay == null && override.shift == null && override.decemberAnchorDay == null) {
            dao.deleteRuleOverride(override.ruleKey)
        } else {
            dao.upsertRuleOverride(override)
        }
    }

    /** Bank holidays for the calendar, refetching only when the stored run is nearly spent. */
    /** Future bank holidays by date, with their names, for warning the user on the day. */
    suspend fun bankHolidays(
        division: String = BankHolidays.DEFAULT_DIVISION,
        today: LocalDate = LocalDate.now(),
    ): Map<LocalDate, String> {
        val stored = dao.countBankHolidaysFrom(division, today.toString())
        if (BankHolidays.needsRefresh(stored)) {
            runCatching { BankHolidays.fetch(today) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { fetched ->
                    dao.upsertBankHolidays(fetched)
                    // Dates that have passed are no longer of use to anything.
                    dao.deleteBankHolidaysBefore(today.toString())
                }
        }
        return dao.bankHolidaysFrom(division, today.toString())
            .mapNotNull { holiday ->
                runCatching { LocalDate.parse(holiday.date) }.getOrNull()?.let { it to holiday.title }
            }
            .toMap()
    }
    val budgetModel: Flow<String?> = secrets.budgetModel

    suspend fun savePrimaryIncomeKey(key: String?) = secrets.savePrimaryIncomeKey(key)

    suspend fun saveBudgetModel(name: String) = secrets.saveBudgetModel(name)

    val accent: Flow<String?> = secrets.accent
    val accountColours: Flow<Boolean> = secrets.accountColours

    suspend fun saveAccent(name: String) = secrets.saveAccent(name)

    suspend fun saveAccountColours(on: Boolean) = secrets.saveAccountColours(on)

    val cardTiming: Flow<String?> = secrets.cardTiming

    suspend fun saveCardTiming(name: String) = secrets.saveCardTiming(name)

    suspend fun setCategoryOverride(accountId: String, transactionId: String, category: String?) {
        // A spend seen in a notification lives in its own table until the bank reports it.
        if (transactionId.startsWith(NotificationSpend.SEEN_PREFIX)) {
            val id = transactionId.removePrefix(NotificationSpend.SEEN_PREFIX)
            dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(categoryOverride = category)) }
            return
        }
        dao.setCategoryOverride(accountId, transactionId, category)
    }

    // --- Spending read from notifications ---

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

    suspend fun onNotification(source: String, title: String?, text: String?, postedAt: Long): Boolean =
        notificationLock.withLock {
            val parsed = NotificationSpend.parse(source, title, text, spendLearned.first())
            val sampleId = dao.insertNotificationSample(
                NotificationSampleEntity(source = source, postedAt = postedAt, title = title, text = text, parsed = parsed != null),
            )
            dao.deleteNotificationSamplesBefore(postedAt - SAMPLE_DAYS_MS)
            dao.deleteSeenSpendsBefore(postedAt - SEEN_SPEND_DAYS_MS)
            val outcome = parsed?.let { countSeen(source, it, postedAt) } ?: NotificationSpend.Outcome.NOT_A_PAYMENT
            dao.setNotificationOutcome(sampleId, outcome.name)
            outcome == NotificationSpend.Outcome.COUNTED
        }

    /**
     * Counts a payment read from a notification - unless it repeats one already seen, or the
     * budget already accounts for it as a transfer or a regular bill.
     */
    private suspend fun countSeen(source: String, parsed: NotificationSpend.Parsed, postedAt: Long): NotificationSpend.Outcome {
        if (!spendReadingOn.first()) return NotificationSpend.Outcome.PAUSED
        val recent = dao.seenSpends()
        if (NotificationSpend.duplicateOf(source, parsed.amountMinor, parsed.cardDigits, postedAt, recent) != null) {
            return NotificationSpend.Outcome.DUPLICATE
        }
        val accounts = dao.accounts()
        val history = accounts.flatMap { dao.transactionsFor(it.id) }
        val rules = RecurringAnalyzer.analyze(history) + manualRules.first().mapNotNull { it.toRecurringRule() }
        if (NotificationSpend.alreadyAccountedFor(parsed, history, rules)) return NotificationSpend.Outcome.ACCOUNTED_FOR
        val placement = NotificationSpend.placementFor(source, parsed, spendSources.first(), accounts, history)
        val accountId = placement?.accountId
        dao.upsertSeenSpend(
            SeenSpendEntity(
                id = "$source|$postedAt",
                source = source,
                seenAt = postedAt,
                amountMinor = -parsed.amountMinor,
                currency = accounts.firstOrNull { it.id == accountId }?.currency ?: "GBP",
                merchant = parsed.merchant,
                cardDigits = parsed.cardDigits,
                accountId = accountId,
                accountGuessed = placement?.guessed == true,
            ),
        )
        reconcileSeenSpends()
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
            seen.filter { it.seenAt in ignored }.forEach { dao.upsertSeenSpend(it.copy(dismissed = true)) }
        }
        return true
    }

    suspend fun forgetLearned() = secrets.updateSpendLearned { emptyList() }

    /**
     * Hands each seen spend over to the bank's row once one reports it, carrying a category set
     * on the seen spend across. Run after every sync and every new notification.
     */
    suspend fun reconcileSeenSpends() {
        // Any purchase counted more than once - as the race before 3.2.1 could - is one again.
        NotificationSpend.repeats(dao.seenSpends().filter { it.matchedTransactionId == null })
            .forEach { dao.upsertSeenSpend(it.copy(dismissed = true)) }
        val seen = dao.seenSpends()
        if (seen.isEmpty()) return
        val bank = dao.accounts().flatMap { dao.transactionsFor(it.id) }
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
    }

    /** The user's answer to "which card?". Remembers the card's digits for next time. */
    suspend fun assignSeenSpend(id: String, accountId: String) {
        val spend = dao.seenSpends().firstOrNull { it.id == id } ?: return
        dao.upsertSeenSpend(spend.copy(accountId = accountId))
        val digits = spend.cardDigits
        val account = dao.accounts().firstOrNull { it.id == accountId }
        if (digits != null && account != null && account.cardLastFour == null) {
            dao.updateAccount(account.copy(cardLastFour = digits))
        }
        reconcileSeenSpends()
    }

    suspend fun dismissSeenSpend(id: String) {
        dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(dismissed = true)) }
    }

    suspend fun keepSeenSpend(id: String) {
        dao.seenSpends().firstOrNull { it.id == id }?.let { dao.upsertSeenSpend(it.copy(keptByUser = true)) }
    }

    suspend fun clearNotificationData() {
        dao.deleteAllSeenSpends()
        dao.deleteAllNotificationSamples()
    }

    /** Rows the bank has stopped listing that the user has yet to answer for. */
    suspend fun duplicateQuestions(): List<DuplicateCheck.Question> =
        DuplicateCheck.questions(dao.duplicateChecks(), transactions())

    suspend fun duplicateChecks(): List<com.spendroid.data.db.DuplicateCheckEntity> = dao.duplicateChecks()

    /** Rows held out of the budget meanwhile, as "accountId|transactionId". */
    suspend fun heldDuplicateKeys(): Set<String> = DuplicateCheck.heldKeys(dao.duplicateChecks())

    /**
     * "Yes, the same": the new row stays, as the bank now lists it, taking every edit the user
     * made to the old one, and the old row goes.
     */
    suspend fun confirmDuplicate(question: DuplicateCheck.Question) {
        val candidate = question.candidate ?: return
        val old = question.vanished
        dao.mergeDuplicate(
            old.accountId,
            old.transactionId,
            candidate.copy(
                isInternalTransfer = old.isInternalTransfer,
                categoryOverride = old.categoryOverride ?: candidate.categoryOverride,
                isCardPayment = old.isCardPayment || candidate.isCardPayment,
                transferOverridden = old.transferOverridden,
            ),
        )
    }

    /** "No, different", or "Keep it": both count, and this row is never asked about again. */
    suspend fun keepVanished(question: DuplicateCheck.Question) {
        dao.upsertDuplicateChecks(listOf(question.check.copy(state = DuplicateCheck.State.KEPT.name, candidates = "")))
    }

    /** "Remove it": the bank no longer lists it - reversed or cancelled - so neither does the app. */
    suspend fun removeVanished(question: DuplicateCheck.Question) {
        dao.deleteTransaction(question.vanished.accountId, question.vanished.transactionId)
        dao.deleteDuplicateCheck(question.vanished.accountId, question.vanished.transactionId)
    }

    suspend fun setInternalTransfer(accountId: String, transactionId: String, isTransfer: Boolean) {
        dao.setInternalTransfer(accountId, transactionId, isTransfer)
    }

    suspend fun setCardPayment(accountId: String, transactionId: String, isPayment: Boolean) {
        dao.setCardPayment(accountId, transactionId, isPayment)
    }

    suspend fun addCategoryRule(pattern: String, category: String) {
        dao.upsertCategoryRule(CategoryRuleEntity(pattern.trim().lowercase(), category))
    }

    suspend fun deleteCategoryRule(pattern: String) {
        dao.deleteCategoryRule(pattern)
    }

    suspend fun setBudgetGoal(category: String, limitMinor: Long, currency: String = "GBP") {
        if (limitMinor <= 0L) dao.deleteBudgetGoal(category)
        else dao.upsertBudgetGoal(BudgetGoalEntity(category, limitMinor, currency))
    }

    suspend fun saveLastNotifiedVersionCode(versionCode: Int) =
        secrets.saveLastNotifiedVersionCode(versionCode)

    suspend fun saveNotifiedGoalWarnings(keys: Set<String>) =
        secrets.saveNotifiedGoalWarnings(keys)

    suspend fun saveNotifiedLargeTransactions(keys: Set<String>) =
        secrets.saveNotifiedLargeTransactions(keys)

    suspend fun saveNotifiedCardWarnings(keys: Set<String>) =
        secrets.saveNotifiedCardWarnings(keys)

    suspend fun setRuleIgnored(key: String, ignored: Boolean) {
        secrets.setRuleIgnored(key, ignored)
    }

    suspend fun addManualRule(rule: ManualRecurringRuleEntity) {
        dao.upsertManualRule(rule)
    }

    /**
     * Makes a payment a regular bill: a monthly rule of its own, so it comes off the budget from
     * the start of each cycle rather than as spending the day it leaves - whether it looked like
     * spending or like money moved between the user's accounts. A rule detected for the same
     * payment is set aside, so the bill is counted once.
     */
    suspend fun treatAsBill(tx: TransactionEntity, cadence: com.spendroid.domain.Cadence = com.spendroid.domain.Cadence.MONTHLY) {
        val date = runCatching { LocalDate.parse(tx.bookingDate) }.getOrElse { LocalDate.now() }
        dao.upsertManualRule(
            ManualRecurringRuleEntity(
                id = "manual-${System.currentTimeMillis()}",
                payee = tx.payee.trim().replace(Regex("\\s+"), " "),
                direction = "OUT",
                amountMinor = kotlin.math.abs(tx.amountMinor),
                currency = tx.currency,
                cadence = cadence.name,
                anchorDay = date.dayOfMonth,
                startDate = date.toString(),
                // Paid from where this one was: a bill on a card is counted in the card's bill.
                accountId = tx.accountId,
            ),
        )
        secrets.setRuleIgnored(RecurringAnalyzer.groupKey(tx), true)
    }

    /** Where a bill added by hand is paid from; null when not said. */
    suspend fun setManualRulePaidFrom(rule: ManualRecurringRuleEntity, accountId: String?) {
        dao.upsertManualRule(rule.copy(accountId = accountId))
    }

    suspend fun deleteManualRule(id: String) {
        dao.deleteManualRule(id)
    }

    suspend fun saveSecret(id: String, key: String) {
        secrets.saveSecret(id, key)
    }

    suspend fun saveNotificationTime(time: String) {
        secrets.saveNotificationTime(time)
    }

    suspend fun saveConnections(connections: List<Connection>) {
        secrets.saveConnections(connections)
    }

    /**
     * Saves an edited account, re-choosing its balance when the type changed.
     *
     * Which balance to show depends on the type, and the pick was only ever made during a
     * sync - so switching an account to Credit card left it showing the figure chosen while
     * it was still a current account, until the next sync came round.
     */
    suspend fun updateAccount(account: AccountEntity) {
        // "In Google Wallet" may have changed, which changes the apps whose notifications are read.
        watched = null
        val previous = dao.accounts().firstOrNull { it.id == account.id }
        if (previous == null || previous.accountType == account.accountType) {
            dao.updateAccount(account)
            return
        }

        // The stored payload only arrives with a sync, so an account that has not synced
        // since this was introduced has none - which is every existing account the first
        // time. Fetch the balances rather than leaving the old figure in place until the
        // next sync, which made changing the type look like it did nothing at all.
        val parsed = account.rawBalancesJson
            ?.let { runCatching { GSON.fromJson(it, BalancesDto::class.java) }.getOrNull() }
            ?: runCatching { dataApi.accountBalances(account.id) }.getOrNull()

        val repicked = parsed
            ?.let { balances ->
                val chosen = selectBalance(balances, account.accountType)
                account.copy(
                    balanceMinor = chosen?.first ?: account.balanceMinor,
                    currency = chosen?.second ?: account.currency,
                    rawBalancesJson = GSON.toJson(balances),
                )
            }
            ?: account

        dao.updateAccount(repicked)
    }

    /** The balance types this bank sent, and which one is in use, for the account sheet. */
    fun balanceTypesFor(account: AccountEntity): List<Pair<String, Boolean>> {
        val parsed = account.rawBalancesJson
            ?.let { runCatching { GSON.fromJson(it, BalancesDto::class.java) }.getOrNull() }
            ?: return emptyList()
        val chosen = selectBalance(parsed, account.accountType)?.first
        return parsed.balances.mapNotNull { balance ->
            val type = balance.balanceType ?: return@mapNotNull null
            val minor = balance.balanceAmount
                ?.let { runCatching { BigDecimal(it.amount).toMinorLong() }.getOrNull() }
            val label = buildString {
                append(type)
                minor?.let { append(" · ").append(it / 100).append(".").append("%02d".format(kotlin.math.abs(it % 100))) }
                if (balance.creditLimitIncluded == true) append(" (includes limit)")
            }
            label to (minor != null && minor == chosen)
        }
    }

    suspend fun clearAllData() {
        watched = null
        clearNotificationData()
        dao.deleteAllDuplicateChecks()
        dao.deleteAllTransactions()
        dao.deleteAllAccounts()
        dao.deleteAllManualRules()
        // Clear the store before the cached token, so nothing can refresh it back in between.
        secrets.clearAll()
        tokenManager.clear()
    }

    suspend fun institutions(country: String): List<InstitutionDto> = dataApi.institutions(country)

    suspend fun createRequisition(institution: InstitutionDto): RequisitionDto =
        dataApi.createRequisition(
            RequisitionRequestDto(
                redirect = REDIRECT_URI,
                institutionId = institution.id,
                reference = "budget-${System.currentTimeMillis()}",
            ),
        )

    suspend fun requisition(id: String): RequisitionDto = dataApi.requisition(id)

    suspend fun pollUntilAuthorised(requisitionId: String): RequisitionDto {
        val startTime = System.currentTimeMillis()
        val timeoutMs = 5 * 60 * 1000 // 5 minutes

        // The user is in a browser for most of this, and leaving and returning to the app is
        // exactly when a phone drops one network and picks up another. A lookup that fails
        // mid-poll is not a failed authorisation, so keep polling until the deadline and only
        // surface a network error if it never recovers.
        var lastNetworkError: Exception? = null
        suspend fun poll(): RequisitionDto? = try {
            dataApi.requisition(requisitionId).also { lastNetworkError = null }
        } catch (e: IOException) {
            lastNetworkError = e
            null
        }

        var req = poll()
        var pollCount = 0
        while (req == null ||
            // GoCardless reports a refusal at the bank as RJ. This waited for "RE", which it never
            // sends, so declining meant five minutes of waiting and then a timeout.
            (req.status != "SA" && req.status != "LN" && req.status !in setOf("EX", "RJ") && req.accounts.isEmpty())
        ) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                lastNetworkError?.let {
                    throw IOException(
                        "Couldn't reach GoCardless - check your connection and try again.", it,
                    )
                }
                error("Polling timed out after 5 minutes. Last status: ${req?.status}, accounts: ${req?.accounts?.size ?: 0}, polls: $pollCount")
            }
            delay(3_000)
            pollCount++
            req = poll() ?: req
            if (req == null) continue
        }
        if (req.status == "EX") error("The link to the bank expired before it was approved. Try again.")
        if (req.status == "RJ") error("Access was declined at the bank, so nothing was linked.")
        return req
    }

    /**
     * Keyed by the consent rather than the bank, so a second login at the same bank - a
     * partner's accounts, a business account - is added alongside rather than replacing the
     * first. [replacing] is the consent this one renews, when it renews one.
     */
    suspend fun saveConnection(connection: Connection, replacing: Connection? = null) {
        secrets.updateConnections { existing ->
            existing.filter {
                it.requisitionId != connection.requisitionId &&
                    it.requisitionId != replacing?.requisitionId
            } + connection
        }
    }

    /**
     * Folds accounts a new consent has reissued under new ids into the copies already stored.
     *
     * Reauthorising issues every account a new id, so without this the old copy and the new
     * one both count the ninety days they overlap, and everything set on the old one - its
     * type, its name, a card's statement day - is on neither. See [matchSuccessors] for when
     * two are taken to be the same account; where that is not certain, both are left.
     */
    suspend fun adoptPredecessors(newConnection: Connection) {
        val all = dao.accounts()
        val newIds = newConnection.accountIds.toSet()
        val otherConnections = connections.first().filter { it.requisitionId != newConnection.requisitionId }
        val pairs = matchSuccessors(
            newAccounts = all.filter { it.id in newIds },
            others = all.filter { it.id !in newIds },
            otherConnections = otherConnections,
        )
        if (pairs.isEmpty()) return

        for ((old, new) in pairs) {
            val successor = refreshedAccount(old.copy(id = new.id), new)
            val history = succeededHistory(dao.transactionsFor(old.id), dao.transactionsFor(new.id), new.id)
            dao.replaceAccount(old.id, successor, history)
        }

        // The old consent no longer answers for those accounts, and one left answering for
        // none is gone. A consent that never listed any is still pending and is kept.
        val adopted = pairs.map { it.first.id }.toSet()
        secrets.updateConnections { stored ->
            stored.mapNotNull { c ->
                if (c.requisitionId == newConnection.requisitionId || c.accountIds.isEmpty()) return@mapNotNull c
                c.copy(accountIds = c.accountIds - adopted).takeIf { it.accountIds.isNotEmpty() }
            }
        }
        analyzeTransactions()
    }

    /**
     * Fills in when access was granted for connections saved before that was kept, from the
     * requisition itself. Without it the expiry is unknown and no reminder can be sent.
     */
    suspend fun backfillConnectionDates() {
        val stored = connections.first()
        if (stored.none { it.createdAt <= 0L }) return
        val created = stored.filter { it.createdAt <= 0L }.mapNotNull { connection ->
            runCatching { dataApi.requisition(connection.requisitionId).created }
                .getOrNull()
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?.let { connection.requisitionId to it }
        }.toMap()
        if (created.isEmpty()) return
        // Applied to whatever is stored by then: the lookups take a while, and a link made
        // meanwhile must not be written over by the list read before them.
        secrets.updateConnections { current ->
            current.map { c -> created[c.requisitionId]?.takeIf { c.createdAt <= 0L }?.let { c.copy(createdAt = it) } ?: c }
        }
    }

    val syncFailures: Flow<Map<String, SyncFailure>> = secrets.syncFailures

    /**
     * Syncs one account, recording why if the bank refuses. A failed sync used to leave the
     * account on its old figures with nothing to say so - while the widget showed another
     * account's newer time - so a salary could sit "pending" for hours for no visible reason.
     */
    suspend fun syncAccount(institutionName: String, accountId: String): AccountEntity =
        try {
            importAccount(institutionName, accountId).also { secrets.setSyncFailure(accountId, null) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            secrets.setSyncFailure(accountId, SyncFailure(System.currentTimeMillis(), failureReason(e)))
            throw e
        } finally {
            // Whatever the bank said about the allowance on the way, success or refusal.
            secrets.saveAllowances(accountId, AllowanceRecorder.take(accountId))
            AllowanceRecorder.takeGeneral()?.let { secrets.saveGeneralAllowance(it) }
        }

    val syncAllowances: Flow<Map<String, Map<SyncAllowance.Scope, SyncAllowance.Reading>>> = secrets.syncAllowances

    /** GoCardless's own limit on all calls, as last reported. */
    val generalAllowance: Flow<SyncAllowance.Reading?> = secrets.generalAllowance

    /**
     * An account's allowance for a full sync right now: the bank's, unless GoCardless's own
     * limit is spent. Null until either has reported.
     */
    suspend fun allowanceFor(accountId: String, now: Long = System.currentTimeMillis()): SyncAllowance.Account? =
        SyncAllowance.forAccount(syncAllowances.first()[accountId].orEmpty(), now, generalAllowance.first())

    suspend fun importAccount(institutionName: String, accountId: String): AccountEntity {
        val metadata: AccountDetailsDto = dataApi.accountMetadata(accountId)
        val details: AccountInfoWrapperDto = runCatching { dataApi.accountDetails(accountId) }.getOrDefault(AccountInfoWrapperDto())
        val info: AccountInfoDto = details.account ?: AccountInfoDto()

        val balances: BalancesDto = dataApi.accountBalances(accountId)
        val accountType = detectAccountType(metadata, info)
        val (balanceMinor, currency) = pickBalance(balances, info, accountType)

        val booked = mutableListOf<TransactionEntity>()
        val pending = mutableListOf<TransactionEntity>()
        // Rows the bank sends without an id are named after their contents, and two
        // identical purchases on one day need telling apart; this counts them as they come.
        val seen = mutableMapOf<String, Int>()
        var page: TransactionsDto? =
            dataApi.accountTransactions(accountId, dateFrom = LocalDate.now().minusDays(90).toString(), dateTo = null)
        while (page != null) {
            page.transactions.booked.forEach { toEntity(accountId, it, pending = false, seen)?.let(booked::add) }
            page.transactions.pending.forEach { toEntity(accountId, it, pending = true, seen)?.let(pending::add) }
            page = page.next?.let { dataApi.transactionsPage(it) }
        }

        // The same rule as the account row below, and for the same reason: a sync must not
        // undo the user's own decisions. REPLACE deletes the old row before inserting, so
        // every category set by hand and every transfer flagged was being wiped nightly for
        // the whole 90-day window.
        // Every pending entry this account held goes, and what the bank reports as pending now
        // comes back - so pending is always exactly the bank's current word. Edits made to a
        // pending row are read first and carried onto whichever row replaces it.
        val stored = dao.transactionsFor(accountId)
        val rows = booked + stillPendingOnly(booked, pending)
        // Booked rows the bank has stopped listing, followed until the user says what they were.
        val checks = DuplicateCheck.afterSync(stored, booked, dao.duplicateChecksFor(accountId), LocalDate.now())
        dao.replaceSync(accountId, preserveUserEdits(rows, stored), checks)

        // A sync refreshes the balance; it must not undo the user's own decisions. Rebuilding
        // the row from scratch reset the type, regenerated the label over any rename, and
        // dropped the card-to-account link entirely - every single day.
        val existing = dao.accounts().firstOrNull { it.id == accountId }
        val fetched = AccountEntity(
            id = accountId,
            institutionName = institutionName,
            label = labelFor(metadata, info, accountId),
            currency = currency,
            balanceMinor = balanceMinor,
            lastSynced = System.currentTimeMillis(),
            accountType = accountType,
            rawBalancesJson = GSON.toJson(balances),
            identity = identityFor(metadata, info),
        )
        val entity = refreshedAccount(existing, fetched)
        dao.upsertAccount(entity)

        analyzeTransactions()
        // The bank may now report what a notification announced.
        reconcileSeenSpends()
        return entity
    }

    /**
     * The budget as the app understands it, assembled from every setting that shapes it.
     *
     * There is one of these because there was nearly not: the widget, the alert worker and
     * the daily roundup each built their own, and each quietly left something out - the
     * roundup omitted accounts entirely, so it counted card spending the home screen
     * excludes. A screen and a notification disagreeing about the same money is the kind of
     * bug nobody reports, because both look plausible on their own.
     */
    /**
     * The moment the budget is worked out at: now, once the current accounts - where salaries
     * land - have synced today; the end of yesterday until then. See [BudgetDay].
     */
    suspend fun budgetTime(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): java.time.LocalDateTime {
        val all = accounts()
        val current = all.filter { it.accountType == AccountType.PERSONAL }.ifEmpty { all }
        val lastSync = current.minOfOrNull { it.lastSynced }
            ?.takeIf { it > 0L }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() }
        val notification = runCatching { java.time.LocalTime.parse(notificationTime.first()) }
            .getOrDefault(java.time.LocalTime.of(21, 0))
        val firstSync = com.spendroid.work.DailyRoundupScheduler.syncTimes(notification).first()
        return BudgetDay.effective(now, lastSync, firstSync)
    }

    suspend fun budgetSnapshot(
        referenceTime: java.time.LocalDateTime? = null,
    ): BudgetSnapshot? {
        val all = transactions()
        if (all.isEmpty()) return null
        return budgetSnapshotOf(all, referenceTime ?: budgetTime())
    }

    /**
     * The one budget calculation, for the app, the widgets and the watch alike. Each used to
     * gather its own inputs, and a setting one of them forgot showed as two different figures.
     */
    suspend fun budgetSnapshotOf(all: List<TransactionEntity>, referenceTime: java.time.LocalDateTime): BudgetSnapshot {
        // A possible re-issue of a row already counted waits for the user before it counts.
        val held = DuplicateCheck.heldKeys(dao.duplicateChecks())
        val counted = if (held.isEmpty()) all else all.filterNot { "${it.accountId}|${it.transactionId}" in held }
        val ignored = ignoredRules.first()
        val manual = manualRules.first().mapNotNull { it.toRecurringRule() }
        val rules = (RecurringAnalyzer.analyze(counted) + manual).filter { it.key !in ignored }
        val holidays = runCatching { bankHolidays() }.getOrDefault(emptyMap())

        return BudgetEngine.snapshot(
            transactions = counted,
            rules = rules,
            accounts = accounts(),
            referenceTime = referenceTime,
            primaryIncomeKey = primaryIncomeKey.first(),
            budgetModel = BudgetModel.from(budgetModel.first()),
            calendar = WorkingDayCalendar(holidays.keys),
            overrides = ruleOverrides.first().associateBy { it.ruleKey },
            cardTiming = CardTiming.from(cardTiming.first()),
        )
    }

    /**
     * Re-runs transfer and recurring detection over what is stored, without asking the bank
     * for anything. A sync does this anyway; a refresh that skips the bank - because every
     * account synced within the hour - still should, or a fix to detection waits a day to show.
     */
    suspend fun reanalyze() = analyzeTransactions()

    private suspend fun analyzeTransactions() {
        val allAccounts = dao.accounts()
        val allTx = allAccounts.flatMap { dao.transactionsFor(it.id) }

        // PayPal is left out of generic transfer detection because PayPalEngine already owns
        // that relationship, and two systems writing the same flag disagree: a top-up paired
        // here would be stored as a transfer, and with PayPal's own rows suppressed the
        // purchase would vanish from spending entirely.
        //
        // Joint accounts are left out for a different reason: paying into a shared pot is
        // the expense, in the same way a card bill is. Flagging it as a transfer would
        // remove the only part of the arrangement that is actually the user's own money.
        val excluded = allAccounts
            .filter {
                it.accountType == AccountType.PAYPAL || it.accountType == AccountType.JOINT
            }
            .map { it.id }
            .toSet()
        val internalPairs = detectInternalTransfers(transferCandidates(allTx, excluded))
        val recurringFlags = RecurringAnalyzer.detectRecurring(allTx)

        // Re-run from scratch rather than only adding: detection used to set the flag and
        // never clear it, so a pair it stopped believing in stayed a transfer for good.
        dao.clearDetectedTransfers()
        internalPairs.forEach { (tx1, tx2) ->
            dao.updateInternalTransfer(tx1.accountId, tx1.transactionId)
            dao.updateInternalTransfer(tx2.accountId, tx2.transactionId)
        }
        // Regular payments the user has said are transfers: every occurrence follows,
        // including ones that arrive later, so the choice is made once rather than monthly.
        inTransferGroups(allTx, secrets.transferGroups.first()).forEach { tx ->
            dao.updateInternalTransfer(tx.accountId, tx.transactionId)
        }
        // The same from-scratch rule as transfers: a payment that stopped recurring stops
        // being flagged, rather than keeping the flag it was once given.
        dao.clearRecurring()
        recurringFlags.forEach { entry ->
            val (key, isRecurring) = entry
            if (isRecurring) {
                val parts = key.split("|")
                if (parts.size == 2) {
                    dao.updateRecurring(parts[0], parts[1])
                }
            }
        }
    }

    suspend fun accounts(): List<AccountEntity> = dao.accounts()

    /** Full serialised copy of the local database, for backup. */
    suspend fun exportJson(): String = BackupExporter.toJson(
        accounts = dao.accounts(),
        // Deliberately not transactions(), which joins through the accounts table and would
        // drop history belonging to an account row that has since gone.
        transactions = dao.allTransactions(),
        manualRules = dao.getActiveManualRules(),
        budgetGoals = dao.budgetGoals(),
        categoryRules = dao.categoryRules(),
        ignoredRules = secrets.ignoredRules.first(),
        transferGroups = secrets.transferGroups.first(),
        ruleOverrides = dao.ruleOverrides(),
        primaryIncomeKey = secrets.primaryIncomeKey.first(),
        budgetModel = secrets.budgetModel.first(),
        cardTiming = secrets.cardTiming.first(),
    )

    /**
     * Merges a backup into the local database, replacing rows that share a primary key and
     * keeping everything else. Credentials are not in a backup and are not touched.
     */
    suspend fun importJson(json: String): BackupImporter.Restored {
        val restored = BackupImporter.parse(json)
        if (restored.accounts.isNotEmpty()) dao.upsertAccounts(restored.accounts)
        if (restored.transactions.isNotEmpty()) dao.upsertTransactions(restored.transactions)
        if (restored.manualRules.isNotEmpty()) dao.upsertManualRules(restored.manualRules)
        restored.budgetGoals.forEach { dao.upsertBudgetGoal(it) }
        restored.categoryRules.forEach { dao.upsertCategoryRule(it) }
        secrets.addIgnoredRules(restored.ignoredRules)
        secrets.addTransferGroups(restored.transferGroups)
        restored.ruleOverrides.forEach { dao.upsertRuleOverride(it) }
        restored.primaryIncomeKey?.let { secrets.savePrimaryIncomeKey(it) }
        restored.budgetModel?.let { secrets.saveBudgetModel(it) }
        restored.cardTiming?.let { secrets.saveCardTiming(it) }
        return restored
    }

    /**
     * Every stored transaction, with PayPal payments resolved to their merchant.
     *
     * Enriching on read rather than on sync keeps it self-correcting: a PayPal row that
     * arrives days after the bank debit still finds its partner, and nothing has to be
     * rewritten in the database to make that happen.
     */
    suspend fun transactions(): List<TransactionEntity> {
        val stored = dao.accounts()
            .flatMap { dao.transactionsFor(it.id) }
            .sortedByDescending { it.bookingDate }
        // Spends seen in notifications and not yet reported by the bank, counted like pending.
        val labels = spendSources.first().associate { it.packageName to it.label } +
            (NotificationSpend.GOOGLE_WALLET to "Google Wallet")
        val known = dao.accounts().mapTo(HashSet()) { it.id }
        val seen = if (spendReadingOn.first()) {
            NotificationSpend.counted(dao.seenSpends())
                .filter { it.accountId in known }
                .map { NotificationSpend.asTransaction(it, labels[it.source] ?: "notification") }
        } else {
            emptyList()
        }
        return PayPalEngine.reconcile((stored + seen).sortedByDescending { it.bookingDate }, dao.accounts())
    }

    private fun toEntity(
        accountId: String,
        tx: TransactionDto,
        pending: Boolean,
        seen: MutableMap<String, Int>,
    ): TransactionEntity? {
        val amount: AmountDto = tx.transactionAmount ?: return null
        val minor = try {
            BigDecimal(amount.amount).toMinorLong()
        } catch (e: Exception) {
            return null
        }
        val payee = payeeFor(tx)
        val txId = tx.transactionId ?: run {
            val content = "${tx.bookingDate}|$minor|$payee"
            val occurrence = (seen[content] ?: 0) + 1
            seen[content] = occurrence
            fallbackTransactionId(tx.bookingDate, minor, payee, occurrence)
        }
        val info = when (val riu = tx.remittanceInformationUnstructured) {
            is List<*> -> riu.firstOrNull()?.toString().orEmpty()
            null -> ""
            else -> riu.toString()
        }
        return TransactionEntity(
            accountId = accountId,
            transactionId = txId,
            bookingDate = tx.bookingDate ?: tx.valueDate ?: "",
            valueDate = tx.valueDate,
            amountMinor = minor,
            currency = amount.currency,
            payee = payee,
            description = info.collapseSpaces().ifBlank { null },
            isPending = pending,
            rawJson = GSON.toJson(tx),
            isInternalTransfer = false,
            isRecurring = false,
        )
    }

    /**
     * Banks send fixed-width fields, so a name arrives padded: "BILLS        NICHO". trim()
     * only touches the ends, so the gap survived into the display and into search, where a
     * query typed with single spaces could never match.
     */
    private fun String.collapseSpaces(): String = trim().replace(Regex("\\s+"), " ")

    private fun payeeFor(tx: TransactionDto): String {
        val direct = sequenceOf(
            tx.counterpartyName,
            tx.debtorName,
            tx.creditorName,
            tx.ultimateDebtorName,
            tx.ultimateCreditorName,
        ).firstOrNull { !it.isNullOrBlank() }
        if (direct != null) return direct.collapseSpaces()
        val info = when (val riu = tx.remittanceInformationUnstructured) {
            is List<*> -> riu.firstOrNull()?.toString().orEmpty()
            null -> ""
            else -> riu.toString()
        }
        return info.collapseSpaces()
    }

    private fun labelFor(metadata: AccountDetailsDto, info: AccountInfoDto, accountId: String): String {
        val parts = mutableListOf<String>()
        metadata.name?.let { parts += it }
        metadata.iban?.let { parts += it }
        info.iban?.let { if (!parts.contains(it)) parts += it }
        info.sortCode?.let { parts += it }
        info.accountNumber?.let { parts += "···${it.takeLast(4)}" }
        if (parts.isEmpty()) {
            metadata.ownerName?.let { parts += it }
        }
        return if (parts.isEmpty()) accountId.substring(0, minOf(8, accountId.length)) else parts.joinToString(" ")
    }

    /**
     * Works out what kind of account this is from everything the bank tells us.
     *
     * It used to read metadata.name alone, which many banks never send - so two credit cards
     * were filed as current accounts, and with them went the card bill forecast, the
     * exclusion of card spending from the budget, and the right choice of balance.
     */
    private fun detectAccountType(metadata: AccountDetailsDto, info: AccountInfoDto): AccountType =
        detectAccountTypeFor(metadata, info)

    private fun pickBalance(balances: BalancesDto, info: AccountInfoDto, accountType: AccountType = AccountType.PERSONAL): Pair<Long?, String> =
        selectBalance(balances, accountType) ?: (null to (info.currency ?: "GBP"))

    companion object {

        /**
         * Carries the user's own edits across a re-sync.
         *
         * The bank is the authority on what a transaction is - its amount, date and payee -
         * but not on what the user decided about it. Those decisions exist nowhere else and
         * cannot be re-derived, so they win over the freshly built row.
         */
        /**
         * The stored account with what the bank knows brought up to date.
         *
         * Built the other way round - a fresh row with the user's settings copied in one by
         * one - every setting added later had to be remembered here too, and the card's
         * statement and payment days were not: a sync wiped them nightly. Starting from the
         * stored row means anything not named below is kept.
         */
        internal fun refreshedAccount(existing: AccountEntity?, fetched: AccountEntity): AccountEntity =
            existing?.copy(
                institutionName = fetched.institutionName,
                currency = fetched.currency,
                balanceMinor = fetched.balanceMinor,
                lastSynced = fetched.lastSynced,
                rawBalancesJson = fetched.rawBalancesJson,
                identity = fetched.identity ?: existing.identity,
            ) ?: fetched

        /**
         * What the bank calls this account whichever consent it arrived under: the IBAN, the
         * UK sort code and number, or a card's masked number, in that order of certainty.
         * Formatting is stripped so "60-16-13" and "601613" agree.
         */
        internal fun identityFor(metadata: AccountDetailsDto, info: AccountInfoDto): String? {
            fun clean(value: String?) = value?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotBlank() }
            clean(info.iban ?: metadata.iban)?.let { return "iban:$it" }
            val sortCode = clean(info.sortCode)
            val number = clean(info.accountNumber)
            if (sortCode != null && number != null) return "uk:$sortCode/$number"
            clean(info.bban ?: metadata.bban)?.let { return "bban:$it" }
            clean(info.maskedPan)?.let { return "pan:$it" }
            return null
        }

        /**
         * Pairs each newly linked account with the stored account it replaces, as (old, new).
         *
         * The same bank's identity is proof, provided exactly one stored account carries it.
         * Failing that - cards often have no number the API will share - an identical name
         * will do, but only among accounts no live consent still holds, or whose consent has
         * just been shown to be the one being replaced. Anything less certain is left alone:
         * a wrong merge would move one person's history onto another's account.
         */
        internal fun matchSuccessors(
            newAccounts: List<AccountEntity>,
            others: List<AccountEntity>,
            otherConnections: List<Connection>,
        ): List<Pair<AccountEntity, AccountEntity>> {
            val pairs = mutableListOf<Pair<AccountEntity, AccountEntity>>()
            val usedOld = mutableSetOf<String>()

            for (new in newAccounts) {
                val identity = new.identity ?: continue
                val match = others
                    .filter { it.institutionName == new.institutionName && it.identity == identity }
                    .singleOrNull() ?: continue
                // Two new accounts claiming one old one is as ambiguous as the reverse.
                if (newAccounts.count { it.identity == identity } != 1) continue
                pairs += match to new
                usedOld += match.id
            }

            val superseded = otherConnections.filter { c -> c.accountIds.any { it in usedOld } }
            val stillHeld = otherConnections.filter { it !in superseded }.flatMap { it.accountIds }.toSet()
            val matchedNew = pairs.map { it.second.id }.toSet()

            for (new in newAccounts.filter { it.id !in matchedNew }) {
                val match = others
                    .filter { old ->
                        old.id !in usedOld &&
                            old.id !in stillHeld &&
                            old.institutionName == new.institutionName &&
                            old.label == new.label &&
                            // Two different numbers are two different accounts, whatever the name.
                            (old.identity == null || new.identity == null || old.identity == new.identity)
                    }
                    .singleOrNull() ?: continue
                pairs += match to new
                usedOld += match.id
            }
            return pairs
        }

        /**
         * The history an account carries into its successor. Before the new consent's first
         * row, the old account is the only copy and moves across; from there on, the fresh
         * fetch is the authority, keeping what the user decided about the rows the two share.
         */
        internal fun succeededHistory(
            old: List<TransactionEntity>,
            fresh: List<TransactionEntity>,
            newId: String,
        ): List<TransactionEntity> {
            val rekeyed = old.map { it.copy(accountId = newId) }
            val start = fresh.mapNotNull { runCatching { LocalDate.parse(it.bookingDate) }.getOrNull() }.minOrNull()
                ?: return rekeyed + fresh
            val before = rekeyed.filter { tx ->
                runCatching { LocalDate.parse(tx.bookingDate) }.getOrNull()?.isBefore(start) == true
            }
            return before + preserveUserEdits(fresh, rekeyed)
        }

        /**
         * An id for a row the bank sent without one, from what it contains. The first of a kind
         * keeps the id rows have always been stored under, so nothing already stored is
         * duplicated; a second identical row on the same day is numbered rather than dropped.
         */
        internal fun fallbackTransactionId(
            bookingDate: String?,
            minor: Long,
            payee: String,
            occurrence: Int,
        ): String {
            val base = "$bookingDate|$minor|$payee".hashCode().toString()
            return if (occurrence <= 1) base else "$base#$occurrence"
        }

        /**
         * The transactions transfer detection may pair.
         *
         * A row the user said is *not* a transfer is theirs, and pairing it anyway marked only
         * the other leg, leaving half a transfer counted and half hidden. A row they said *is*
         * one stays in: it is still one half of a move, and without it its other half has
         * nothing to pair with and is counted as spending. That was 2.24's mistake - it left
         * out every row the user had ruled on, either way, and a £500 move to savings whose
         * receiving side had been marked by hand turned into a £500 monthly commitment.
         */
        /**
         * The transactions a marked group covers. A row the user individually said is not a
         * transfer keeps their word: the narrower decision wins over the broader one.
         */
        internal fun inTransferGroups(
            transactions: List<TransactionEntity>,
            groups: Set<String>,
        ): List<TransactionEntity> {
            if (groups.isEmpty()) return emptyList()
            return transactions.filter { tx ->
                RecurringAnalyzer.groupKey(tx) in groups && !(tx.transferOverridden && !tx.isInternalTransfer)
            }
        }

        internal fun transferCandidates(
            transactions: List<TransactionEntity>,
            excludedAccounts: Set<String>,
        ): List<TransactionEntity> = transactions.filter {
            it.accountId !in excludedAccounts && !(it.transferOverridden && !it.isInternalTransfer)
        }

        /**
         * Pairs off the two halves of a move between the user's own accounts.
         *
         * The payee is deliberately not required to match. It names the *counterparty*, so the
         * two legs of the same transfer are named differently by design - the money leaves as
         * "JOINT ACCOUNT BILLS" and arrives as "BILLS NICHO". Insisting they agree meant genuine
         * transfers were never paired, and the standing order into the joint account was read as
         * a monthly salary.
         *
         * What is required instead is that the pairing be unambiguous. Only the user's own
         * accounts are visible here, so an exact opposite amount within a day is already strong
         * evidence; where more than one transaction could be the other half, the references
         * break the tie, and if they cannot, nothing is paired. A wrong pair hides real spending,
         * which is worse than leaving a transfer on show.
         */
        internal fun detectInternalTransfers(
            transactions: List<TransactionEntity>,
        ): List<Pair<TransactionEntity, TransactionEntity>> {
            // A fixed order, whatever order the accounts arrived in. It used to follow the
            // order they last synced, so the same data could pair one way at 16:26 and another
            // at 16:50 - and did, turning a transfer into a £500 commitment.
            val sorted = transactions.sortedWith(
                compareBy<TransactionEntity>({ it.bookingDate }, { it.accountId }, { it.transactionId }),
            )
            val credits = sorted.filter { it.amountMinor > 0 }.groupBy { it.amountMinor }
            val debits = sorted.filter { it.amountMinor < 0 }.groupBy { -it.amountMinor }

            fun creditsFor(debit: TransactionEntity) = credits[-debit.amountMinor].orEmpty()
                .filter { it.accountId != debit.accountId && couldOffset(debit, it) }
            fun debitsFor(credit: TransactionEntity) = debits[credit.amountMinor].orEmpty()
                .filter { it.accountId != credit.accountId && couldOffset(it, credit) }

            val pairs = mutableListOf<Pair<TransactionEntity, TransactionEntity>>()
            val used = mutableSetOf<String>()
            fun open(list: List<TransactionEntity>) = list.filter { transferKey(it) !in used }

            /**
             * The credit this debit pairs with, when the pairing is certain from both ends.
             *
             * One-sided certainty was the old rule, and it is not enough: a credit that two
             * debits could claim was taken by whichever came first, even when its reference
             * named the other. So a pair needs each side to be the other's only match - or,
             * failing that, the references to single out one pairing on both sides.
             */
            fun partner(debit: TransactionEntity): TransactionEntity? {
                val options = open(creditsFor(debit))
                options.singleOrNull()?.let { credit ->
                    if (open(debitsFor(credit)).singleOrNull() == debit) return credit
                }
                val named = options.filter { referencesAgree(debit, it) }.singleOrNull() ?: return null
                val namedBack = open(debitsFor(named)).filter { referencesAgree(it, named) }.singleOrNull()
                return named.takeIf { namedBack == debit }
            }

            // Each pair made can settle another that was ambiguous only because of it.
            var progress = true
            while (progress) {
                progress = false
                for (debit in sorted) {
                    if (debit.amountMinor >= 0 || transferKey(debit) in used) continue
                    val credit = partner(debit) ?: continue
                    pairs += debit to credit
                    used += transferKey(debit)
                    used += transferKey(credit)
                    progress = true
                }
            }
            return pairs
        }

        private fun transferKey(tx: TransactionEntity) = "${tx.accountId}|${tx.transactionId}"

        /** Equal and opposite, same currency, close enough in time to be one movement. */
        private fun couldOffset(tx1: TransactionEntity, tx2: TransactionEntity): Boolean {
            if (tx1.amountMinor != -tx2.amountMinor || tx1.currency != tx2.currency) return false
            val date1 = try { LocalDate.parse(tx1.bookingDate) } catch (_: Exception) { return false }
            val date2 = try { LocalDate.parse(tx2.bookingDate) } catch (_: Exception) { return false }
            return Math.abs(date1.toEpochDay() - date2.toEpochDay()) <= 1
        }

        /** Whether the two sides name the same movement, used only to break a tie. */
        private fun referencesAgree(tx1: TransactionEntity, tx2: TransactionEntity): Boolean {
            val ref1 = (tx1.description ?: "").trim().lowercase()
            val ref2 = (tx2.description ?: "").trim().lowercase()
            val payee1 = tx1.payee.trim().lowercase()
            val payee2 = tx2.payee.trim().lowercase()

            if (ref1.isNotBlank() && ref1 == ref2) return true
            if (payee1.isNotBlank() && payee1 == payee2) return true
            if (ref1.isNotBlank() && ref2.isNotBlank()) {
                val a = ref1.split("[^a-z0-9]+".toRegex()).filter { it.length > 2 }.toSet()
                val b = ref2.split("[^a-z0-9]+".toRegex()).filter { it.length > 2 }.toSet()
                if (a.isNotEmpty() && a == b) return true
            }
            return false
        }

        /**
         * The pending entries the bank has not also reported as booked.
         *
         * Some banks go on listing a payment as pending after it has booked. Under the same id,
         * the pending copy was written after the booked one and replaced it, every sync - and,
         * still being listed, it was never cleared: a salary the bank showed as landed stayed
         * pending in the app. Under a new id, the stale copy sat beside the booked one for good.
         * A booked entry wins. A pending one is dropped when it shares the booked one's id, or
         * is the same amount from the same payee, booked on or within a few days of it.
         */
        internal fun stillPendingOnly(
            booked: List<TransactionEntity>,
            pending: List<TransactionEntity>,
        ): List<TransactionEntity> {
            val bookedIds = booked.mapTo(HashSet()) { it.transactionId }
            val claimed = mutableSetOf<String>()
            fun payee(tx: TransactionEntity) = tx.payee.lowercase().trim().replace(Regex("\\s+"), " ")
            // The words a name is made of, less digits and short fragments: "GREGGS 4168" and
            // "GREGGS PLC AMESBURY" share "greggs".
            fun words(tx: TransactionEntity) = payee(tx).split(Regex("[^a-z]+")).filter { it.length >= 3 }.toSet()
            fun inWindow(p: TransactionEntity, b: TransactionEntity): Boolean {
                val pendingDate = runCatching { LocalDate.parse(p.bookingDate) }.getOrNull() ?: return true
                val bookedOn = runCatching { LocalDate.parse(b.bookingDate) }.getOrNull() ?: return false
                return !bookedOn.isBefore(pendingDate.minusDays(1)) && !bookedOn.isAfter(pendingDate.plusDays(PENDING_TWIN_DAYS))
            }
            return pending.filter { p ->
                if (p.transactionId in bookedIds) return@filter false
                val candidates = booked.filter { b -> b.transactionId !in claimed && b.amountMinor == p.amountMinor && inWindow(p, b) }
                // The same name; else a name sharing a word - NatWest lists a card payment pending
                // as "GREGGS 4168" and books it as "GREGGS PLC AMESBURY", and went on listing
                // the pending one too, so a £4.25 counted twice; else the only pairing there is -
                // one pending, one booked, same amount, in the window.
                val twin = candidates.firstOrNull { payee(it) == payee(p) }
                    ?: candidates.firstOrNull { (words(it) intersect words(p)).isNotEmpty() }
                    ?: candidates.singleOrNull()?.takeIf {
                        pending.count { other -> other.amountMinor == p.amountMinor && inWindow(other, it) } == 1
                    }
                if (twin != null) {
                    claimed += twin.transactionId
                    false
                } else {
                    true
                }
            }
        }

        /** Whether the user has decided anything about this row that a sync must not lose. */
        private fun TransactionEntity.hasUserEdits(): Boolean =
            categoryOverride != null || transferOverridden || isCardPayment

        /** How long after a pending entry its booked version may be dated. */
        private const val PENDING_TWIN_DAYS = 5L

        /** What a refusal from the bank means, in the terms the account card uses. */
        internal fun failureReason(e: Throwable): SyncFailure.Reason = when {
            e is retrofit2.HttpException && e.code() == 429 -> SyncFailure.Reason.LIMIT
            e is retrofit2.HttpException && e.code() in setOf(401, 403, 409) -> SyncFailure.Reason.REAUTH
            e is IOException -> SyncFailure.Reason.OFFLINE
            else -> SyncFailure.Reason.ERROR
        }

        internal fun preserveUserEdits(
            fetched: List<TransactionEntity>,
            existing: List<TransactionEntity>,
        ): List<TransactionEntity> {
            if (existing.isEmpty()) return fetched
            val byId = existing.associateBy { it.transactionId }
            // A pending row often books under a new id. Matched by id alone, a category set
            // while it was pending was lost the moment it booked; its booked twin - same amount,
            // same payee, dated within the pending window - inherits the edits instead.
            val fetchedIds = fetched.mapTo(HashSet()) { it.transactionId }
            val orphanedPending = existing
                .filter { it.isPending && it.transactionId !in fetchedIds && it.hasUserEdits() }
                .toMutableList()
            fun normalised(tx: TransactionEntity) = tx.payee.lowercase().trim().replace(Regex("\\s+"), " ")
            fun twinOf(row: TransactionEntity): TransactionEntity? {
                if (row.isPending) return null
                val bookedOn = runCatching { LocalDate.parse(row.bookingDate) }.getOrNull() ?: return null
                val twin = orphanedPending.firstOrNull { p ->
                    p.amountMinor == row.amountMinor && normalised(p) == normalised(row) &&
                        runCatching { LocalDate.parse(p.bookingDate) }.getOrNull()?.let { pendingOn ->
                            !bookedOn.isBefore(pendingOn.minusDays(1)) &&
                                !bookedOn.isAfter(pendingOn.plusDays(PENDING_TWIN_DAYS))
                        } ?: true
                } ?: return null
                orphanedPending.remove(twin)
                return twin
            }
            return fetched.map { row ->
                val prior = byId[row.transactionId] ?: twinOf(row) ?: return@map row
                row.copy(
                    isInternalTransfer = prior.isInternalTransfer,
                    isRecurring = prior.isRecurring,
                    categoryOverride = prior.categoryOverride,
                    isCardPayment = prior.isCardPayment,
                    transferOverridden = prior.transferOverridden,
                )
            }
        }
            internal fun detectAccountTypeFor(metadata: AccountDetailsDto, info: AccountInfoDto): AccountType {
            // ISO 20022 cash account type: an outright answer where the bank sends one.
            if (info.cashAccountType?.equals("CARD", ignoreCase = true) == true) {
                return AccountType.CREDIT_CARD
            }

            val text = listOfNotNull(metadata.name, info.name, info.product, info.details)
                .joinToString(" ")
                .lowercase()

            return when {
                // Before the card rules: PayPal calls its product a "card" often enough to
                // be mistaken for one, and it behaves nothing like a credit card here.
                text.contains("paypal") -> AccountType.PAYPAL
                text.contains("credit card") || text.contains("creditcard") -> AccountType.CREDIT_CARD
                text.contains("credit") && !text.contains("credit union") -> AccountType.CREDIT_CARD
                text.contains("joint") -> AccountType.JOINT
                text.contains("saving") || text.contains("isa ") || text.endsWith(" isa") ->
                    AccountType.SAVINGS
                // Weakest signal last, so "card" does not outrank an explicit "joint".
                text.contains("card") -> AccountType.CREDIT_CARD
                else -> AccountType.PERSONAL
            }
        }

        /**
         * Picks which of a bank's several balances to show.
         *
         * For a current account the useful figure is what is available to spend. For a credit
         * card it is the opposite: "available" means the unused part of the limit, so preferring
         * it showed a card's headroom as though it were money. Banks differ in which types they
         * return, which is why one card looked right and another did not - an issuer that omits
         * interimAvailable fell through to the real balance by luck rather than by design.
         */

        /**
         * Picks which of a bank's several balances to show.
         *
         * For a current account the useful figure is what is available to spend. For a credit
         * card it is the opposite: "available" means the unused part of the limit, so
         * preferring it showed a card's headroom as though it were money. Banks differ in
         * which types they return, which is why one card looked right and another did not -
         * an issuer that omits interimAvailable fell through to the real balance by luck.
         */
        fun selectBalance(balances: BalancesDto, accountType: AccountType): Pair<Long, String>? {
            val preference = when (accountType) {
                // Owed first, and never an "available" type, which is unused credit.
                AccountType.CREDIT_CARD ->
                    listOf("closingBooked", "interimBooked", "expected", "openingBooked")
                else -> listOf("interimAvailable", "expected", "closingBooked")
            }
            for (type in preference) {
                val balance = balances.balances.firstOrNull {
                    it.balanceType == type &&
                        it.balanceAmount != null &&
                        // Whatever it is called, a figure carrying the credit limit is
                        // headroom rather than a balance.
                        it.creditLimitIncluded != true
                } ?: continue
                val amount = balance.balanceAmount ?: continue
                return try {
                    BigDecimal(amount.amount).toMinorLong() to amount.currency
                } catch (e: Exception) {
                    continue
                }
            }

            // Nothing preferred was on offer. For a card, any figure the bank has not flagged
            // as containing the limit beats showing the headroom as though it were a debt.
            if (accountType == AccountType.CREDIT_CARD) {
                balances.balances
                    .firstOrNull {
                        it.balanceAmount != null &&
                            it.creditLimitIncluded != true &&
                            it.balanceType?.contains("available", ignoreCase = true) != true
                    }
                    ?.balanceAmount
                    ?.let { amount ->
                        return runCatching {
                            BigDecimal(amount.amount).toMinorLong() to amount.currency
                        }.getOrNull()
                    }
            }
            return null
        }

        private const val SAMPLE_DAYS_MS = 30L * 24 * 60 * 60_000L
        // Long after the bank has reported them; kept until then only so a match can be made.
        private const val SEEN_SPEND_DAYS_MS = 60L * 24 * 60 * 60_000L

        private const val BASE_URL = "https://bankaccountdata.gocardless.com/api/v2/"
        // A custom scheme MainActivity registers, so the bank hands control back to the
        // app. This used to be http://localhost:8080, which nothing serves - the browser
        // just showed a connection error and the user had to find their way back.
        private const val REDIRECT_URI = "spendroid://link-complete"
        private val GSON: Gson = Gson()

        fun create(context: Context, dao: BudgetDao): GoCardlessRepository {
            val secrets = SecretsStore(context)

            val authApi: GcAuthApi = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GcAuthApi::class.java)

            val tokenManager = TokenManager(authApi, secrets)

            // Request lines name account ids, and the system log is readable by anything with
            // debugging access to the phone - so only debug builds write them.
            val logging = HttpLoggingInterceptor().apply {
                level = if (com.spendroid.BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
            val dataClient = OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                // The token goes on every request up front. Leaving it to the authenticator
                // sent each call twice - once bare, to be refused, and again with the token -
                // against an API that rations calls per account per day.
                .addInterceptor { chain ->
                    val token = runBlocking { tokenManager.get() }
                    chain.proceed(
                        chain.request().newBuilder().header("Authorization", "Bearer $token").build(),
                    )
                }
                .addInterceptor(logging)
                // The bank's allowance for each account, as every rationed response reports it.
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    SyncAllowance.fromGeneralHeaders({ response.header(it) }, System.currentTimeMillis())
                        ?.let { AllowanceRecorder.recordGeneral(it) }
                    SyncAllowance.scopeFor(chain.request().url.encodedPath)?.let { (accountId, scope) ->
                        val now = System.currentTimeMillis()
                        val reading = when {
                            response.isSuccessful -> SyncAllowance.fromHeaders({ response.header(it) }, now)
                            response.code == 429 -> SyncAllowance.fromRefusal(response.peekBody(4_096).string(), now)
                            else -> null
                        }
                        reading?.let { AllowanceRecorder.record(accountId, scope, it) }
                    }
                    response
                }
                .authenticator { _, response ->
                    // A 401 on a token that looked valid means it was revoked early: drop it and
                    // try once more with a fresh one. Once only, so a refusal that is about the
                    // bank's consent rather than the token cannot loop.
                    if (response.priorResponse != null) {
                        null
                    } else {
                        tokenManager.clear()
                        val token = runBlocking { tokenManager.get() }
                        response.request.newBuilder().header("Authorization", "Bearer $token").build()
                    }
                }
                .build()

            val dataApi: GcDataApi = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(dataClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GcDataApi::class.java)

            return GoCardlessRepository(secrets, tokenManager, dataApi, dao)
        }
    }
}
