package com.spendroid.data

import android.content.Context
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetDao
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
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
import com.spendroid.domain.RecurringAnalyzer
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
    val manualRules: Flow<List<ManualRecurringRuleEntity>> = dao.activeManualRulesFlow()
    val notificationTime: Flow<String> = secrets.notificationTime
    val lastNotifiedVersionCode: Flow<Int> = secrets.lastNotifiedVersionCode
    val categoryRules: Flow<List<CategoryRuleEntity>> = dao.categoryRulesFlow()
    val budgetGoals: Flow<List<BudgetGoalEntity>> = dao.budgetGoalsFlow()
    val primaryIncomeKey: Flow<String?> = secrets.primaryIncomeKey
    val budgetModel: Flow<String?> = secrets.budgetModel

    suspend fun savePrimaryIncomeKey(key: String?) = secrets.savePrimaryIncomeKey(key)

    suspend fun saveBudgetModel(name: String) = secrets.saveBudgetModel(name)

    suspend fun setCategoryOverride(accountId: String, transactionId: String, category: String?) {
        dao.setCategoryOverride(accountId, transactionId, category)
    }

    suspend fun setInternalTransfer(accountId: String, transactionId: String, isTransfer: Boolean) {
        dao.setInternalTransfer(accountId, transactionId, isTransfer)
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

    suspend fun updateAccount(account: AccountEntity) {
        dao.updateAccount(account)
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

    suspend fun saveConnection(connection: Connection) {
        val existing = connections.first()
        saveConnections(existing.filter { it.institutionId != connection.institutionId } + connection)
    }

    suspend fun importAccount(institutionName: String, accountId: String): AccountEntity {
        val metadata: AccountDetailsDto = dataApi.accountMetadata(accountId)
        val details: AccountInfoWrapperDto = runCatching { dataApi.accountDetails(accountId) }.getOrDefault(AccountInfoWrapperDto())
        val info: AccountInfoDto = details.account ?: AccountInfoDto()

        val balances: BalancesDto = dataApi.accountBalances(accountId)
        val accountType = detectAccountType(metadata, info)
        val (balanceMinor, currency) = pickBalance(balances, info, accountType)

        val rows = mutableListOf<TransactionEntity>()
        var page: TransactionsDto? =
            dataApi.accountTransactions(accountId, dateFrom = LocalDate.now().minusDays(90).toString(), dateTo = null)
        while (page != null) {
            page.transactions.booked.forEach { toEntity(accountId, it, pending = false)?.let(rows::add) }
            page.transactions.pending.forEach { toEntity(accountId, it, pending = true)?.let(rows::add) }
            page = page.next?.let { dataApi.transactionsPage(it) }
        }

        dao.upsertTransactions(rows)

        // A sync refreshes the balance; it must not undo the user's own decisions. Rebuilding
        // the row from scratch reset the type, regenerated the label over any rename, and
        // dropped the card-to-account link entirely - every single day.
        val existing = dao.accounts().firstOrNull { it.id == accountId }
        val entity = AccountEntity(
            id = accountId,
            institutionName = institutionName,
            label = existing?.label ?: labelFor(metadata, info, accountId),
            currency = currency,
            balanceMinor = balanceMinor,
            lastSynced = System.currentTimeMillis(),
            accountType = existing?.accountType ?: accountType,
            linkedCreditCardAccountId = existing?.linkedCreditCardAccountId,
        )
        dao.upsertAccount(entity)

        analyzeTransactions()
        return entity
    }

    private suspend fun analyzeTransactions() {
        val allAccounts = dao.accounts()
        val allTx = allAccounts.flatMap { dao.transactionsFor(it.id) }
        val internalPairs = detectInternalTransfers(allTx)
        val recurringFlags = RecurringAnalyzer.detectRecurring(allTx)

        internalPairs.forEach { (tx1, tx2) ->
            dao.updateInternalTransfer(tx1.accountId, tx1.transactionId)
            dao.updateInternalTransfer(tx2.accountId, tx2.transactionId)
        }
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

    private fun detectInternalTransfers(transactions: List<TransactionEntity>): List<Pair<TransactionEntity, TransactionEntity>> {
        val byAccount = transactions.groupBy { it.accountId }
        val accountIds = byAccount.keys.toList()
        val pairs = mutableListOf<Pair<TransactionEntity, TransactionEntity>>()
        val used = mutableSetOf<String>()

        for (i in accountIds.indices) {
            for (j in (i + 1) until accountIds.size) {
                val txs1 = byAccount[accountIds[i]] ?: emptyList()
                val txs2 = byAccount[accountIds[j]] ?: emptyList()

                for (tx1 in txs1) {
                    if (used.contains("${tx1.accountId}|${tx1.transactionId}")) continue
                    for (tx2 in txs2) {
                        if (used.contains("${tx2.accountId}|${tx2.transactionId}")) continue
                        if (isLikelyInternalTransfer(tx1, tx2)) {
                            pairs.add(tx1 to tx2)
                            used.add("${tx1.accountId}|${tx1.transactionId}")
                            used.add("${tx2.accountId}|${tx2.transactionId}")
                            break
                        }
                    }
                }
            }
        }
        return pairs
    }

    private fun isLikelyInternalTransfer(tx1: TransactionEntity, tx2: TransactionEntity): Boolean {
        if (tx1.amountMinor != -tx2.amountMinor || tx1.currency != tx2.currency) return false
        val date1 = try { LocalDate.parse(tx1.bookingDate) } catch (_: Exception) { return false }
        val date2 = try { LocalDate.parse(tx2.bookingDate) } catch (_: Exception) { return false }
        if (Math.abs(date1.toEpochDay() - date2.toEpochDay()) > 1) return false

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
        return restored
    }

    suspend fun transactions(): List<TransactionEntity> = dao.accounts()
        .flatMap { dao.transactionsFor(it.id) }
        .sortedByDescending { it.bookingDate }

    private fun toEntity(accountId: String, tx: TransactionDto, pending: Boolean): TransactionEntity? {
        val amount: AmountDto = tx.transactionAmount ?: return null
        val minor = try {
            BigDecimal(amount.amount).toMinorLong()
        } catch (e: Exception) {
            return null
        }
        val payee = payeeFor(tx)
        val txId = tx.transactionId ?: "${tx.bookingDate}|$minor|$payee".hashCode().toString()
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
            description = info.ifBlank { null },
            isPending = pending,
            rawJson = GSON.toJson(tx),
            isInternalTransfer = false,
            isRecurring = false,
        )
    }

    private fun payeeFor(tx: TransactionDto): String {
        val direct = sequenceOf(
            tx.counterpartyName,
            tx.debtorName,
            tx.creditorName,
            tx.ultimateDebtorName,
            tx.ultimateCreditorName,
        ).firstOrNull { !it.isNullOrBlank() }
        if (direct != null) return direct.trim()
        val info = when (val riu = tx.remittanceInformationUnstructured) {
            is List<*> -> riu.firstOrNull()?.toString().orEmpty()
            null -> ""
            else -> riu.toString()
        }
        return info.trim()
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
            internal fun detectAccountTypeFor(metadata: AccountDetailsDto, info: AccountInfoDto): AccountType {
            // ISO 20022 cash account type: an outright answer where the bank sends one.
            if (info.cashAccountType?.equals("CARD", ignoreCase = true) == true) {
                return AccountType.CREDIT_CARD
            }

            val text = listOfNotNull(metadata.name, info.name, info.product, info.details)
                .joinToString(" ")
                .lowercase()

            return when {
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
                .addInterceptor(logging)
                .authenticator { _, response ->
                    // Guard against an infinite 401 loop: never re-attach a token
                    // to a request that already carried one.
                    if (response.request.header("Authorization") != null) {
                        null
                    } else {
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
