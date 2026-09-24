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
    val connections: Flow<List<Connection>> = secrets.connections
    val ignoredRules: Flow<Set<String>> = secrets.ignoredRules
    val notifiedGoalWarnings: Flow<Set<String>> = secrets.notifiedGoalWarnings
    val notifiedLargeTransactions: Flow<Set<String>> = secrets.notifiedLargeTransactions
    val notifiedCardWarnings: Flow<Set<String>> = secrets.notifiedCardWarnings
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

    val cardTiming: Flow<String?> = secrets.cardTiming

    suspend fun saveCardTiming(name: String) = secrets.saveCardTiming(name)

    suspend fun setCategoryOverride(accountId: String, transactionId: String, category: String?) {
        dao.setCategoryOverride(accountId, transactionId, category)
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
            (req.status != "SA" && req.status != "LN" && req.status !in setOf("EX", "RE") && req.accounts.isEmpty())
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
        if (req.status == "EX") error("Requisition expired. Status: EX, accounts: ${req.accounts.size}, polls: $pollCount")
        if (req.status == "RE") error("Requisition rejected. Status: RE, accounts: ${req.accounts.size}, polls: $pollCount")
        return req
    }

    /**
     * Keyed by the consent rather than the bank, so a second login at the same bank - a
     * partner's accounts, a business account - is added alongside rather than replacing the
     * first. [replacing] is the consent this one renews, when it renews one.
     */
    suspend fun saveConnection(connection: Connection, replacing: Connection? = null) {
        val existing = connections.first()
        saveConnections(
            existing.filter {
                it.requisitionId != connection.requisitionId &&
                    it.requisitionId != replacing?.requisitionId
            } + connection,
        )
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
        saveConnections(
            connections.first().mapNotNull { c ->
                if (c.requisitionId == newConnection.requisitionId || c.accountIds.isEmpty()) return@mapNotNull c
                c.copy(accountIds = c.accountIds - adopted).takeIf { it.accountIds.isNotEmpty() }
            },
        )
        analyzeTransactions()
    }

    /**
     * Fills in when access was granted for connections saved before that was kept, from the
     * requisition itself. Without it the expiry is unknown and no reminder can be sent.
     */
    suspend fun backfillConnectionDates() {
        val stored = connections.first()
        if (stored.none { it.createdAt <= 0L }) return
        val filled = stored.map { connection ->
            if (connection.createdAt > 0L) return@map connection
            val created = runCatching { dataApi.requisition(connection.requisitionId).created }
                .getOrNull()
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: return@map connection
            connection.copy(createdAt = created)
        }
        if (filled != stored) saveConnections(filled)
    }

    suspend fun importAccount(institutionName: String, accountId: String): AccountEntity {
        val metadata: AccountDetailsDto = dataApi.accountMetadata(accountId)
        val details: AccountInfoWrapperDto = runCatching { dataApi.accountDetails(accountId) }.getOrDefault(AccountInfoWrapperDto())
        val info: AccountInfoDto = details.account ?: AccountInfoDto()

        val balances: BalancesDto = dataApi.accountBalances(accountId)
        val accountType = detectAccountType(metadata, info)
        val (balanceMinor, currency) = pickBalance(balances, info, accountType)

        val rows = mutableListOf<TransactionEntity>()
        // Rows the bank sends without an id are named after their contents, and two
        // identical purchases on one day need telling apart; this counts them as they come.
        val seen = mutableMapOf<String, Int>()
        var page: TransactionsDto? =
            dataApi.accountTransactions(accountId, dateFrom = LocalDate.now().minusDays(90).toString(), dateTo = null)
        while (page != null) {
            page.transactions.booked.forEach { toEntity(accountId, it, pending = false, seen)?.let(rows::add) }
            page.transactions.pending.forEach { toEntity(accountId, it, pending = true, seen)?.let(rows::add) }
            page = page.next?.let { dataApi.transactionsPage(it) }
        }

        // The same rule as the account row below, and for the same reason: a sync must not
        // undo the user's own decisions. REPLACE deletes the old row before inserting, so
        // every category set by hand and every transfer flagged was being wiped nightly for
        // the whole 90-day window.
        dao.upsertTransactions(preserveUserEdits(rows, dao.transactionsFor(accountId)))

        // Anything still marked pending that the bank has stopped sending has either been
        // booked under a new id or withdrawn. Either way the row is stale, and now that
        // pending counts towards spending a stale one is a phantom that never clears.
        val stillPending = rows.filter { it.isPending }.map { it.transactionId }
        if (stillPending.isEmpty()) {
            dao.expireAllPending(accountId)
        } else {
            dao.expirePending(accountId, stillPending)
        }

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
    suspend fun budgetSnapshot(
        referenceTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ): BudgetSnapshot? {
        val all = transactions()
        if (all.isEmpty()) return null

        val ignored = ignoredRules.first()
        val manual = manualRules.first().mapNotNull { it.toRecurringRule() }
        val rules = (RecurringAnalyzer.analyze(all) + manual).filter { it.key !in ignored }
        val holidays = runCatching { bankHolidays() }.getOrDefault(emptyMap())

        return BudgetEngine.snapshot(
            transactions = all,
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
        return PayPalEngine.reconcile(stored, dao.accounts())
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

        internal fun preserveUserEdits(
            fetched: List<TransactionEntity>,
            existing: List<TransactionEntity>,
        ): List<TransactionEntity> {
            if (existing.isEmpty()) return fetched
            val byId = existing.associateBy { it.transactionId }
            return fetched.map { row ->
                val prior = byId[row.transactionId] ?: return@map row
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

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
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
