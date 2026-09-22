package com.spendroid.data

import android.content.Context
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetDao
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
    val manualRules: Flow<List<ManualRecurringRuleEntity>> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(dao.getActiveManualRules())
            kotlinx.coroutines.delay(1_000)
        }
    }
    val notificationTime: Flow<String> = secrets.notificationTime

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
        var req = dataApi.requisition(requisitionId)
        var pollCount = 0
        while (req.status != "SA" && req.status != "LN" && req.status !in setOf("EX", "RE") && req.accounts.isEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                error("Polling timed out after 5 minutes. Last status: ${req.status}, accounts: ${req.accounts.size}, polls: $pollCount")
            }
            delay(3_000)
            pollCount++
            req = dataApi.requisition(requisitionId)
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

        val entity = AccountEntity(
            id = accountId,
            institutionName = institutionName,
            label = labelFor(metadata, info, accountId),
            currency = currency,
            balanceMinor = balanceMinor,
            lastSynced = System.currentTimeMillis(),
            accountType = accountType,
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
        if (tx1.amountMinor == -tx2.amountMinor && tx1.currency == tx2.currency) {
            val date1 = try { LocalDate.parse(tx1.bookingDate) } catch (_: Exception) { return false }
            val date2 = try { LocalDate.parse(tx2.bookingDate) } catch (_: Exception) { return false }
            if (Math.abs(date1.toEpochDay() - date2.toEpochDay()) <= 1) {
                val ref1 = (tx1.description ?: "").lowercase()
                val ref2 = (tx2.description ?: "").lowercase()
                if (ref1.isNotBlank() && ref2.isNotBlank() && ref1 == ref2) return true
                return true // same amount, opposite, same day ±1
            }
        }
        return false
    }

    suspend fun accounts(): List<AccountEntity> = dao.accounts()

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

    private fun detectAccountType(metadata: AccountDetailsDto, info: AccountInfoDto): AccountType {
        val name = (metadata.name ?: "").lowercase()
        val combined = name
        return when {
            combined.contains("credit") || combined.contains("card") -> AccountType.CREDIT_CARD
            combined.contains("joint") -> AccountType.JOINT
            combined.contains("saving") -> AccountType.SAVINGS
            else -> AccountType.PERSONAL
        }
    }

    private fun pickBalance(balances: BalancesDto, info: AccountInfoDto, accountType: AccountType = AccountType.PERSONAL): Pair<Long?, String> {
        val target = when (accountType) {
            AccountType.CREDIT_CARD -> listOf("interimAvailable", "expected", "available", "closingBooked")
            else -> listOf("interimAvailable", "expected", "closingBooked")
        }
        for (type in target) {
            val balance = balances.balances.firstOrNull { it.balanceType == type && it.balanceAmount != null }
                ?: continue
            val amount = balance.balanceAmount ?: continue
            return try {
                BigDecimal(amount.amount).toMinorLong() to amount.currency
            } catch (e: Exception) {
                continue
            }
        }
        return null to (info.currency ?: "GBP")
    }

    companion object {
        private const val BASE_URL = "https://ob.nordigen.com/api/v2/"
        private const val REDIRECT_URI = "http://localhost:8080/budgetapp"
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
                    val token = runBlocking { tokenManager.get() }
                    response.request.newBuilder().header("Authorization", "Bearer $token").build()
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