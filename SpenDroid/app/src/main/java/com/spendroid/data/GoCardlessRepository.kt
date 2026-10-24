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
import com.spendroid.domain.CardDigits
import com.spendroid.domain.NotificationSpend
import com.spendroid.domain.countedOn
import com.spendroid.domain.ManualAccounts
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
            seen.setCategory(id, category)
            return
        }
        dao.setCategoryOverride(accountId, transactionId, category)
    }

    // --- Spending read from notifications: [SeenSpendStore] ---
    private val seen = SeenSpendStore(dao, secrets)

    val spendSources: Flow<List<NotificationSpend.Source>> = seen.spendSources
    val spendReadingOn: Flow<Boolean> = seen.spendReadingOn
    val spendLearned: Flow<List<NotificationSpend.Learned>> = seen.spendLearned
    val seenSpends: Flow<List<SeenSpendEntity>> = seen.seenSpends
    val notificationSamples: Flow<List<NotificationSampleEntity>> = seen.notificationSamples
    val spendPlaces: Flow<Map<String, String>> = seen.spendPlaces

    suspend fun saveSpendSources(sources: List<NotificationSpend.Source>) = seen.saveSpendSources(sources)
    suspend fun setSpendReadingOn(on: Boolean) = seen.setSpendReadingOn(on)
    suspend fun watchedPackages() = seen.watchedPackages()
    suspend fun questionFor(source: String, postedAt: Long) = seen.questionFor(source, postedAt)
    suspend fun placeOf(spend: SeenSpendEntity) = seen.placeOf(spend)
    suspend fun settledSince(since: Long) = seen.settledSince(since)
    suspend fun onNotification(source: String, title: String?, text: String?, postedAt: Long) = seen.onNotification(source, title, text, postedAt)
    suspend fun teachNotification(sampleId: Long, count: Boolean) = seen.teachNotification(sampleId, count)
    suspend fun forgetLearned() = seen.forgetLearned()
    suspend fun reconcileSeenSpends() = seen.reconcileSeenSpends()
    suspend fun assignSeenSpend(id: String, accountId: String, always: Boolean = false) = seen.assignSeenSpend(id, accountId, always)
    suspend fun repeatQuestionFor(postedAt: Long) = seen.repeatQuestionFor(postedAt)
    suspend fun answerRepeat(spendId: String, twinId: String, once: Boolean) = seen.answerRepeat(spendId, twinId, once)
    suspend fun dismissSeenSpend(id: String) = seen.dismissSeenSpend(id)
    suspend fun answerCardCheck(id: String, check: Boolean) = seen.answerCardCheck(id, check)
    suspend fun keepSeenSpend(id: String) = seen.keepSeenSpend(id)
    suspend fun clearNotificationData() = seen.clearNotificationData()

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

    /** Every stored row for one account, imported ones included. */
    suspend fun storedTransactions(accountId: String): List<TransactionEntity> = dao.transactionsFor(accountId)

    /**
     * Adds imported rows. For a manual account, [closing] - the statement's closing balance and
     * its day - becomes the balance known for certain, balance-check entries the statement now
     * explains go, and payments seen in notifications are paired with the statement's rows.
     */
    suspend fun addImported(rows: List<TransactionEntity>, closing: Pair<LocalDate, Long>? = null) {
        dao.upsertTransactions(rows)
        rows.groupBy { it.accountId }.forEach { (accountId, imported) ->
            val account = dao.accounts().firstOrNull { it.id == accountId && it.manual } ?: return@forEach
            val days = imported.mapNotNull { RecurringAnalyzer.parseBookingDate(it.bookingDate) }
            if (days.isNotEmpty()) {
                ManualAccounts.explained(dao.transactionsFor(accountId), days.min(), days.max())
                    .forEach { dao.deleteTransaction(accountId, it.transactionId) }
            }
            closing?.let { (day, minor) -> ManualAccounts.anchorFrom(account, day, minor)?.let { dao.updateAccount(it) } }
        }
        reanalyze()
        reconcileSeenSpends()
    }

    suspend fun undoImport(accountId: String, batchId: String) {
        dao.deleteTransactionsWithPrefix(accountId, "${DuplicateCheck.IMPORTED_PREFIX}$batchId:")
        reanalyze()
    }

    /** An import moved to another account: its rows taken off the one they went to, [rows] added in their place. */
    suspend fun replaceImport(fromAccountId: String, batchId: String, rows: List<TransactionEntity>) {
        dao.deleteTransactionsWithPrefix(fromAccountId, "${DuplicateCheck.IMPORTED_PREFIX}$batchId:")
        if (rows.isNotEmpty()) dao.upsertTransactions(rows)
        reanalyze()
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
        seen.forgetWatched()
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
                val chosen = SyncRules.selectBalance(balances, account.accountType)
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
        val chosen = SyncRules.selectBalance(parsed, account.accountType)?.first
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
        seen.forgetWatched()
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
        val pairs = SyncRules.matchSuccessors(
            newAccounts = all.filter { it.id in newIds },
            others = all.filter { it.id !in newIds },
            otherConnections = otherConnections,
        )
        if (pairs.isEmpty()) return

        for ((old, new) in pairs) {
            val successor = SyncRules.refreshedAccount(old.copy(id = new.id), new)
            val history = SyncRules.succeededHistory(dao.transactionsFor(old.id), dao.transactionsFor(new.id), new.id)
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
            secrets.setSyncFailure(accountId, SyncFailure(System.currentTimeMillis(), SyncRules.failureReason(e)))
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

    private suspend fun cardDigits(): Set<String> = seen.cardDigits()

    /**
     * Once, after card digits came off pending names (5.2.2): a rule made from one - "always
     * file WAITROSE STORES 4168 as Groceries" - becomes the shop's name, so it goes on matching.
     */
    suspend fun tidyCardDigitRules() {
        val cards = cardDigits()
        val rules = dao.categoryRules()
        val patterns = rules.mapTo(HashSet()) { it.pattern }
        rules.forEach { rule ->
            val trimmed = CardDigits.trim(rule.pattern, cards)
            if (trimmed == rule.pattern) return@forEach
            if (trimmed !in patterns) dao.upsertCategoryRule(rule.copy(pattern = trimmed))
            dao.deleteCategoryRule(rule.pattern)
        }
    }

    /**
     * One sync at a time: the nightly sync and a pull to refresh landing together each read the
     * stored rows before the other wrote, and each spent one of the bank's few syncs a day.
     */
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    suspend fun importAccount(institutionName: String, accountId: String): AccountEntity {
        val asked = System.currentTimeMillis()
        return syncLock.withLock {
            // Synced while this waited its turn: that's the fresh copy, and the bank's allowance kept.
            dao.accounts().firstOrNull { it.id == accountId && it.lastSynced >= asked }
                ?: importAccountNow(institutionName, accountId)
        }
    }

    private suspend fun importAccountNow(institutionName: String, accountId: String): AccountEntity {
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
        // A pending card payment named with the card's digits - "WAITROSE STORES 4168" - is
        // shown and filed by the shop's name. Only pending ones: they're rebuilt every sync and
        // never make a regular payment, so nothing kept is keyed on the old name. Their made-up
        // ids were worked out from the name as sent, so they stay the same from sync to sync.
        val cards = cardDigits()
        val rows = booked + SyncRules.stillPendingOnly(booked, pending).map { it.copy(payee = CardDigits.trim(it.payee, cards)) }
        // Booked rows the bank has stopped listing, followed until the user says what they were.
        val checks = DuplicateCheck.afterSync(stored, booked, dao.duplicateChecksFor(accountId), LocalDate.now())
        dao.replaceSync(accountId, SyncRules.preserveUserEdits(rows, stored), checks)

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
            identity = SyncRules.identityFor(metadata, info),
        )
        val entity = SyncRules.refreshedAccount(existing, fetched)
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
        // Manual accounts never sync: with only those, the budget is as of now.
        val all = accounts().filter { !it.manual }
        if (all.isEmpty()) return now
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
    /** What's ahead - the forecast, dips below zero, bills' price changes - from the same inputs as the budget. */
    suspend fun outlook(
        snapshot: BudgetSnapshot?,
        all: List<TransactionEntity>,
        includeVariable: Boolean,
        subscriptionKinds: Map<String, com.spendroid.domain.Subscriptions.Kind> = emptyMap(),
        contractEnds: Map<String, java.time.LocalDate> = emptyMap(),
        acceptedContracts: Set<String> = emptySet(),
    ): com.spendroid.domain.Outlook {
        val manual = manualRules.first().mapNotNull { it.toRecurringRule() }
        return com.spendroid.domain.Outlook.of(
            snapshot = snapshot,
            accounts = accounts(),
            rules = RecurringAnalyzer.analyze(all) + manual,
            ignored = ignoredRules.first(),
            transactions = all,
            today = java.time.LocalDate.now(),
            includeVariable = includeVariable,
            calendar = com.spendroid.domain.WorkingDayCalendar(runCatching { bankHolidays() }.getOrDefault(emptyMap()).keys),
            overrides = ruleOverrides.first().associateBy { it.ruleKey },
            subscriptionKinds = subscriptionKinds,
            contractEnds = contractEnds,
            acceptedContracts = acceptedContracts,
        )
    }

    /**
     * The last pay cycle, looked back on. Each day's figures are the budget as it stood at that
     * day's end - the engine run once a day of the cycle - so they agree with what Home said.
     * Kept until the cycle changes, as it takes a moment to work out.
     */
    suspend fun lastCycleReview(): com.spendroid.domain.CycleReview? {
        val all = transactions()
        if (all.isEmpty()) return null
        val now = budgetSnapshotOf(all, budgetTime())
        reviewCache?.takeIf { it.first == now.cycleStart }?.let { return it.second }
        val end = now.cycleStart.minusDays(1)
        val last = budgetSnapshotOf(all, end.atTime(23, 59))
        if (!last.cycleStart.isBefore(now.cycleStart)) return null
        val days = generateSequence(last.cycleStart) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        if (days.isEmpty() || days.size > 62) return null
        val each = days.map { day -> day to budgetSnapshotOf(all, day.atTime(23, 59)) }
        val daily = each.map { (day, snap) -> day to snap.spentToday }
        val share = last.spendableThisCycle.takeIf { it > 0L }?.let { it / days.size } ?: 0L
        val review = com.spendroid.domain.CycleReview(
            start = last.cycleStart,
            end = end,
            currency = last.baseCurrency,
            leftOverMinor = last.availableToSpend,
            spentMinor = last.spentThisCycle,
            daily = daily,
            dailyShareMinor = share,
            daysAhead = each.count { (_, snap) -> com.spendroid.domain.BudgetPace.of(snap) == com.spendroid.domain.BudgetPace.Pace.ON_TRACK },
            biggestDay = daily.maxByOrNull { it.second }?.takeIf { it.second > 0L },
            mostVisited = com.spendroid.domain.CycleReview.mostVisited(all, last.cycleStart, end),
        )
        reviewCache = now.cycleStart to review
        return review
    }

    @Volatile private var reviewCache: Pair<LocalDate, com.spendroid.domain.CycleReview>? = null

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

    // --- Accounts with no bank link ---

    /** Works each manual account's balance out again from its anchor and transactions. */
    private suspend fun refreshManualBalances() {
        dao.accounts().filter { it.manual }.forEach { account ->
            val balance = ManualAccounts.balance(account, dao.transactionsFor(account.id))
            if (balance != account.balanceMinor) dao.updateAccount(account.copy(balanceMinor = balance))
        }
    }

    /** An account added by hand: no bank link, kept up to date from statements and notifications. */
    suspend fun addManualAccount(label: String, bank: String, type: AccountType, balanceMinor: Long?): AccountEntity {
        val account = AccountEntity(
            id = "manual:" + java.util.UUID.randomUUID().toString().take(12),
            institutionName = bank.ifBlank { "My bank" },
            label = label.ifBlank { "My account" },
            currency = "GBP",
            balanceMinor = balanceMinor,
            lastSynced = 0L,
            accountType = type,
            manual = true,
            anchorMinor = balanceMinor,
            anchorDate = balanceMinor?.let { LocalDate.now().toString() },
        )
        dao.upsertAccount(account)
        seen.forgetWatched()
        return account
    }

    /** Removes a manual account and everything stored for it. Bank-linked accounts can't be removed here. */
    suspend fun deleteManualAccount(accountId: String) {
        val account = dao.accounts().firstOrNull { it.id == accountId } ?: return
        if (!account.manual) return
        dao.deleteTransactions(accountId)
        dao.deleteAccount(accountId)
        // Banking apps stop speaking for it; payments counted on it go with it.
        secrets.saveSpendSources(spendSources.first().map { s ->
            s.copy(accountIds = s.accountIds - accountId, defaultAccountId = s.defaultAccountId?.takeIf { it != accountId })
        })
        dao.seenSpends().filter { it.countedOn == accountId }.forEach { dao.upsertSeenSpend(it.copy(dismissed = true)) }
        seen.forgetWatched()
        reanalyze()
    }

    /** Sets a manual account's balance as of today, as the user typed it. */
    suspend fun setManualBalance(accountId: String, balanceMinor: Long) {
        val account = dao.accounts().firstOrNull { it.id == accountId && it.manual } ?: return
        dao.updateAccount(account.copy(anchorMinor = balanceMinor, anchorDate = LocalDate.now().toString()))
        refreshManualBalances()
    }

    /** For each manual account, the last day an imported statement covers. */
    suspend fun manualCoverage(): Map<String, LocalDate?> = seen.manualCoverage()

    /**
     * Checks a manual account's balance against what the user's banking app shows - nothing is
     * saved until [applyBalanceCheck].
     */
    suspend fun previewBalanceCheck(accountId: String, typedMinor: Long): ManualAccounts.Check? {
        val account = dao.accounts().firstOrNull { it.id == accountId && it.manual } ?: return null
        val seen = dao.seenSpends()
        val sources = spendSources.first()
        val walletAccounts = dao.accounts().filter { it.walletLinked }.map { it.id }.toSet()
        // Payments only guessed onto another account - or still waiting to be told - that this
        // one's banking app, or Wallet, could have paid.
        val elsewhere = NotificationSpend.counted(seen).filter { spend ->
            (spend.accountGuessed || spend.accountId == null) && spend.countedOn != accountId &&
                if (spend.source == NotificationSpend.GOOGLE_WALLET) accountId in walletAccounts
                else sources.firstOrNull { it.packageName == spend.source }?.accountIds?.contains(accountId) == true
        }
        return ManualAccounts.check(account, typedMinor, dao.transactionsFor(accountId), seen, elsewhere)
    }

    /**
     * Records a balance check: [moveSpendId], when the user agreed a payment guessed onto another
     * account was really this one's, moves it first; then the balance is set to what the bank
     * showed, and any gap still unexplained is counted as spending.
     */
    suspend fun applyBalanceCheck(accountId: String, typedMinor: Long, moveSpendId: String? = null): ManualAccounts.Check? {
        moveSpendId?.let { assignSeenSpend(it, accountId) }
        val check = previewBalanceCheck(accountId, typedMinor) ?: return null
        val account = dao.accounts().first { it.id == accountId }
        val (anchored, entry) = ManualAccounts.afterCheck(
            account, check, dao.seenSpends(), LocalDate.now(), java.util.UUID.randomUUID().toString().take(8),
        )
        dao.updateAccount(anchored)
        entry?.let { dao.upsertTransactions(listOf(it)) }
        reanalyze()
        return check
    }

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
        val internalPairs = SyncRules.detectInternalTransfers(SyncRules.transferCandidates(allTx, excluded))
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
        SyncRules.inTransferGroups(allTx, secrets.transferGroups.first()).forEach { tx ->
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
        // A manual account's balance follows its transactions: an import, an undo, a move.
        refreshManualBalances()
    }

    suspend fun accounts(): List<AccountEntity> = dao.accounts()

    /** Full serialised copy of the local database, for backup. */
    /** A backup: everything - with the bank link only if [includeBank], for an encrypted one. */
    suspend fun exportJson(preferences: org.json.JSONObject? = null, includeBank: Boolean = false): String = BackupExporter.toJson(
        preferences = preferences,
        store = secrets.exportStore(includeBank),
        duplicateChecks = dao.duplicateChecks(),
        notificationSamples = dao.notificationSamples(),
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
        seenSpends = dao.seenSpends(),
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
        restored.seenSpends.forEach { dao.upsertSeenSpend(it) }
        restored.primaryIncomeKey?.let { secrets.savePrimaryIncomeKey(it) }
        restored.budgetModel?.let { secrets.saveBudgetModel(it) }
        restored.cardTiming?.let { secrets.saveCardTiming(it) }
        if (restored.duplicateChecks.isNotEmpty()) dao.upsertDuplicateChecks(restored.duplicateChecks)
        if (restored.notificationSamples.isNotEmpty()) dao.upsertNotificationSamples(restored.notificationSamples)
        restored.store?.let { secrets.restoreStore(it) }
        // New keys from the backup: a token got with the old ones mustn't be used with them.
        if (restored.hasBank) tokenManager.clear()
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
                .filter { it.countedOn in known }
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
        // A pending row has no booking date yet, and often no id: the value date stands in for
        // the date, or every £11.35 lunch at one place got the same made-up id - and a card
        // alert matched to last week's lunch was taken to have claimed this week's too.
        val txId = tx.transactionId ?: SyncRules.madeUpId(tx.bookingDate, tx.valueDate, minor, payee, seen)
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
        SyncRules.detectAccountTypeFor(metadata, info)

    private fun pickBalance(balances: BalancesDto, info: AccountInfoDto, accountType: AccountType = AccountType.PERSONAL): Pair<Long?, String> =
        SyncRules.selectBalance(balances, accountType) ?: (null to (info.currency ?: "GBP"))

    companion object {

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
