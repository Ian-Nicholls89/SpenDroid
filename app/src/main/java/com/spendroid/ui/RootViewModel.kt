package com.spendroid.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import com.spendroid.BudgetApplication
import com.spendroid.data.Connection
import com.spendroid.data.GoCardlessRepository
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.InstitutionDto
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.RecurringRule
import com.spendroid.work.UpdateCheckerWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import java.util.regex.Pattern

sealed interface UpdateCheckStatus {
    data class Checking(val message: String) : UpdateCheckStatus
    data class Success(val message: String) : UpdateCheckStatus
    data class Error(val message: String) : UpdateCheckStatus
    object Idle : UpdateCheckStatus
}

data class RootUiState(
    val hasCredentials: Boolean = false,
    val linkingBank: String? = null,
    val error: String? = null,
    val syncing: Boolean = false,
    val institutions: List<InstitutionDto> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val budget: BudgetSnapshot? = null,
    val rules: List<RecurringRule> = emptyList(),
    val manualRules: List<ManualRecurringRuleEntity> = emptyList(),
    val ignoredRules: Set<String> = emptySet(),
    val reauthNeeded: List<Connection> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val showRecurringOnly: Boolean = true,
    val showInternalTransfers: Boolean = false,
    val updateCheckStatus: UpdateCheckStatus = UpdateCheckStatus.Idle,
)

class RootViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: GoCardlessRepository = (app as BudgetApplication).repository

    private val _state = MutableStateFlow(RootUiState(connections = emptyList(), manualRules = emptyList(), showRecurringOnly = true, showInternalTransfers = false))
    val state: StateFlow<RootUiState> = _state.asStateFlow()

    private val _secretId = MutableStateFlow("")
    val secretIdValue: StateFlow<String> = _secretId.asStateFlow()

    private val _secretKey = MutableStateFlow("")
    val secretKeyValue: StateFlow<String> = _secretKey.asStateFlow()

    val notificationTime: StateFlow<String> = repo.notificationTime
        .map { it ?: "21:00" }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), "21:00")

    init {
        viewModelScope.launch {
            refreshConfig()
        }
        viewModelScope.launch {
            repo.secretId.filterNotNull().collect { _secretId.value = it }
        }
        viewModelScope.launch {
            repo.secretKey.filterNotNull().collect { _secretKey.value = it }
        }
    }

    private suspend fun refreshConfig() {
        val hasCredentials = repo.secretId.first() != null && repo.secretKey.first() != null
        _state.update { it.copy(hasCredentials = hasCredentials) }
        if (hasCredentials) {
            loadInstitutions("")
            loadLocal()
        }
    }

    fun saveSecret(id: String, key: String) {
        viewModelScope.launch {
            runCatching { repo.saveSecret(id.trim(), key.trim()) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            refreshConfig()
        }
    }

    fun saveNotificationTime(time: String) {
        viewModelScope.launch {
            repo.saveNotificationTime(time)
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Checking("Checking for updates…")) }
            val versionCode = try {
                getApplication<android.app.Application>().packageManager.getPackageInfo(getApplication<android.app.Application>().packageName, 0).longVersionCode.toInt()
            } catch (e: Exception) {
                0
            }
            val inputData = androidx.work.Data.Builder().putInt(UpdateCheckerWorker.VERSION_CODE_KEY, versionCode).build()
            androidx.work.WorkManager.getInstance(getApplication<android.app.Application>())
                .enqueueUniqueWork(UpdateCheckerWorker::class.java.simpleName, androidx.work.ExistingWorkPolicy.REPLACE, androidx.work.OneTimeWorkRequestBuilder<UpdateCheckerWorker>().setInputData(inputData).build())
            
            // Poll for result (the worker posts notification, but we also check version here)
            try {
                val latest = checkForUpdateResult()
                latest?.let { (versionCode, versionName) ->
                    if (versionCode > 0) {
                        _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Success("Update found: v$versionName (build $versionCode)")) }
                    } else {
                        _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Success("You're up to date")) }
                    }
                } ?: _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Error("Unable to check for updates")) }
            } catch (e: Exception) {
                _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Error("Error: ${e.message}")) }
            }
        }
    }

    private suspend fun checkForUpdateResult(): Pair<Int, String>? {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/Ian-Nicholls89/SpenDroid/releases/latest")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val json = org.json.JSONObject(body)

        // Try release body
        val releaseBody = json.optString("body", "")
        val versionFromBody = extractVersionCode(releaseBody)
        val nameFromBody = extractVersionName(releaseBody)
        if (versionFromBody != null) return Pair(versionFromBody, nameFromBody ?: "")

        // Try assets
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val versionFromAsset = extractVersionCode(name)
                if (versionFromAsset != null) return Pair(versionFromAsset, extractVersionName(name) ?: "")
            }
        }

        // Fallback: tag
        val tagName = json.optString("tag_name", "")
        val versionFromTag = extractVersionCode(tagName)
        val nameFromTag = extractVersionName(tagName)
        if (versionFromTag != null) return Pair(versionFromTag, nameFromTag ?: "")

        return null
    }

    private fun extractVersionCode(text: String): Int? {
        val patterns = listOf(
            Pattern.compile("versionCode[\\s:=]+(\\d+)"),
            Pattern.compile("version[\\s:=]+(\\d+)"),
            Pattern.compile("v(\\d+)(?:\\.\\d+)?[-\\.]"),
            Pattern.compile("app[-\\s](\\d+)\\."),
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1).toIntOrNull()
            }
        }
        return null
    }

    private fun extractVersionName(text: String): String? {
        val patterns = listOf(
            Pattern.compile("versionName[\\s:=]+([^\\s\\n]+)"),
            Pattern.compile("version[\\s:=]+([\\d.]+)"),
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    fun loadInstitutions(query: String) {
        viewModelScope.launch {
            runCatching { repo.institutions("GB") }
                .onSuccess { all ->
                    val q = query.trim().lowercase()
                    val filtered =
                        if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
                    _state.update { it.copy(institutions = filtered) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun link(institution: InstitutionDto) {
        viewModelScope.launch {
            doLink(institution)
        }
    }

    fun relink(connection: Connection) {
        viewModelScope.launch {
            val institution = _state.value.institutions.firstOrNull { it.id == connection.institutionId }
                ?: runCatching { repo.institutions("GB") }
                    .getOrNull()
                    ?.firstOrNull { it.id == connection.institutionId }
            if (institution == null) {
                _state.update { it.copy(error = "${connection.institutionName} is no longer available.") }
                return@launch
            }
            doLink(institution)
        }
    }

    fun setRuleIgnored(key: String, ignored: Boolean) {
        viewModelScope.launch {
            repo.setRuleIgnored(key, ignored)
            loadLocal()
        }
    }

    fun toggleRecurringOnly() {
        _state.update { it.copy(showRecurringOnly = !it.showRecurringOnly) }
    }

    fun toggleInternalTransfers() {
        _state.update { it.copy(showInternalTransfers = !it.showInternalTransfers) }
    }

    fun clearData() {
        viewModelScope.launch {
            repo.saveConnections(emptyList())
            repo.saveSecret("", "")
            _state.update { RootUiState(connections = emptyList(), manualRules = emptyList(), showRecurringOnly = true, showInternalTransfers = false) }
            _secretId.value = ""
            _secretKey.value = ""
        }
    }

    fun addManualRule(payee: String, direction: String, amountMinor: Long, currency: String, cadence: String, anchorDay: Int, startDate: String) {
        viewModelScope.launch {
            val id = "manual-${System.currentTimeMillis()}"
            val rule = ManualRecurringRuleEntity(
                id = id,
                payee = payee,
                direction = direction,
                amountMinor = amountMinor,
                currency = currency,
                cadence = cadence,
                anchorDay = anchorDay,
                startDate = startDate,
            )
            repo.addManualRule(rule)
            loadLocal()
        }
    }

    fun addManualRuleFromCandidate(candidate: RecurringAnalyzer.RecurringCandidate) {
        addManualRule(
            payee = candidate.payee,
            direction = candidate.direction.name,
            amountMinor = candidate.amountMinor,
            currency = candidate.currency,
            cadence = candidate.cadence.name,
            anchorDay = candidate.anchorDay,
            startDate = candidate.startDate.toString(),
        )
    }

    suspend fun getRecurringCandidates(): List<RecurringAnalyzer.RecurringCandidate> = RecurringAnalyzer.findCandidates(repo.transactions())

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch {
            repo.updateAccount(account)
            loadLocal()
        }
    }

    private suspend fun doLink(institution: InstitutionDto) {
        _state.update { it.copy(linkingBank = institution.name, error = null) }
        runCatching {
            _state.update { it.copy(error = "Creating requisition…") }
            val req = repo.createRequisition(institution)
            _state.update { it.copy(error = "Requisition created: ${req.id}, status: ${req.status}") }
            val link = req.link ?: error("GoCardless did not return a link")
            val appContext = getApplication<Application>().applicationContext
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(appContext, Uri.parse(link))
            _state.update { it.copy(error = "Opened bank auth. Waiting for authorisation…") }
            val done = repo.pollUntilAuthorised(req.id ?: error("Missing requisition id"))
            _state.update { it.copy(error = "Authorisation complete. Status: ${done.status}, accounts: ${done.accounts.size}") }
            if (done.accounts.isEmpty()) error("No accounts returned by ${institution.name}. Status: ${done.status}")
            val connection = Connection(
                institutionId = institution.id,
                institutionName = institution.name,
                requisitionId = done.id ?: req.id!!,
                accountIds = done.accounts,
            )
            repo.saveConnection(connection)
            _state.update { it.copy(error = "Importing ${done.accounts.size} account(s)…") }
            done.accounts.forEach { repo.importAccount(institution.name, it) }
        }
            .onSuccess {
                _state.update {
                    it.copy(
                        linkingBank = null,
                        error = null,
                        reauthNeeded = it.reauthNeeded.filter { c -> c.institutionId != institution.id },
                    )
                }
                loadLocal()
            }
            .onFailure { e ->
                _state.update { it.copy(linkingBank = null, error = "Link failed: ${e.message}") }
            }
        _state.update { it.copy(linkingBank = null) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            val reauth = mutableListOf<Connection>()
            runCatching {
                repo.connections.first().forEach { connection ->
                    var ok = false
                    if (connection.accountIds.isNotEmpty()) {
                        ok = runCatching {
                            connection.accountIds.forEach { repo.importAccount(connection.institutionName, it) }
                        }.isSuccess
                    } else {
                        val req = runCatching { repo.requisition(connection.requisitionId) }.getOrNull()
                        if (req != null && req.status == "SA" && req.accounts.isNotEmpty()) {
                            repo.saveConnection(connection.copy(accountIds = req.accounts))
                            ok = runCatching {
                                req.accounts.forEach { repo.importAccount(connection.institutionName, it) }
                            }.isSuccess
                        }
                    }
                    if (!ok) reauth += connection
                }
            }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(reauthNeeded = reauth) }
            loadLocal()
            _state.update { it.copy(syncing = false) }
        }
    }

    private suspend fun loadLocal() {
        val all = repo.transactions()
        val rules = RecurringAnalyzer.analyze(all)
        val ignored = repo.ignoredRules.first()
        val manualRules = repo.manualRules.first()
        val accounts = repo.accounts()
        _state.update {
            it.copy(
                accounts = accounts,
                transactions = all.take(500),
                rules = rules,
                manualRules = manualRules,
                ignoredRules = ignored,
                budget = BudgetEngine.snapshot(all, rules.filter { rule -> rule.key !in ignored }, accounts),
                connections = repo.connections.first(),
            )
        }
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { RootViewModel(app) }
        }
    }
}