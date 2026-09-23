package com.spendroid.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spendroid.BudgetApplication
import com.spendroid.BuildConfig
import com.spendroid.data.Connection
import com.spendroid.data.UpdateChecker
import com.spendroid.data.GoCardlessRepository
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.InstitutionDto
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.RecurringRule
import com.spendroid.domain.toRecurringRule
import com.spendroid.work.DailyRoundupScheduler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted

sealed interface ImportStatus {
    object Idle : ImportStatus
    object Working : ImportStatus
    data class Done(val message: String) : ImportStatus
    data class Failed(val message: String) : ImportStatus
}

sealed interface ExportStatus {
    object Idle : ExportStatus
    object Working : ExportStatus
    data class Done(val message: String) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

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
    val exportStatus: ExportStatus = ExportStatus.Idle,
    val importStatus: ImportStatus = ImportStatus.Idle,
    val versionName: String = "",
    val versionCode: Int = 0,
)

class RootViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: GoCardlessRepository = (app as BudgetApplication).repository

    private val _state = MutableStateFlow(RootUiState(
        connections = emptyList(),
        manualRules = emptyList(),
        showRecurringOnly = true,
        showInternalTransfers = false,
        versionName = "",
        versionCode = 0,
    ))
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
        // Blank rather than just absent: installs cleared by an older build stored empty
        // strings, which are non-null and would otherwise read as valid credentials.
        val hasCredentials = !repo.secretId.first().isNullOrBlank() &&
            !repo.secretKey.first().isNullOrBlank()
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
            DailyRoundupScheduler.schedule(getApplication())
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Checking("Checking for updates…")) }
            // Checks directly rather than enqueueing the worker as well - doing both fired two
            // requests per tap and the two results could disagree.
            val status = try {
                val latest = UpdateChecker.latestRelease()
                when {
                    latest == null ->
                        UpdateCheckStatus.Error("Unable to check for updates")
                    UpdateChecker.isNewer(latest) ->
                        UpdateCheckStatus.Success("Update found: v${latest.versionName} (build ${latest.versionCode})")
                    else ->
                        UpdateCheckStatus.Success("You're up to date (v${latest.versionName}, build ${BuildConfig.VERSION_CODE})")
                }
            } catch (e: Exception) {
                UpdateCheckStatus.Error("Error: ${e.message ?: e.javaClass.simpleName}")
            }
            _state.update { it.copy(updateCheckStatus = status) }
        }
    }

    private var cachedInstitutions: List<InstitutionDto>? = null

    fun loadInstitutions(query: String) {
        viewModelScope.launch {
            val all = runCatching {
                cachedInstitutions?.takeIf { it.isNotEmpty() }
                    ?: repo.institutions("GB").also { cachedInstitutions = it }
            }.getOrElse { e ->
                _state.update { it.copy(error = e.message) }
                return@launch
            }
            val q = query.trim().lowercase()
            val filtered =
                if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
            _state.update { it.copy(institutions = filtered) }
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

    /** Writes a full backup to a location the user picked through the system file picker. */
    fun exportTo(destination: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(exportStatus = ExportStatus.Working) }
            val status = runCatching {
                val json = repo.exportJson()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destination)
                        ?.use { out -> out.write(json.toByteArray()) }
                        ?: error("Could not open the selected file for writing")
                }
                json.length
            }.fold(
                onSuccess = { ExportStatus.Done("Backup saved (${it / 1024} KB)") },
                onFailure = { ExportStatus.Failed(it.message ?: it.javaClass.simpleName) },
            )
            _state.update { it.copy(exportStatus = status) }
        }
    }

    /** Merges a backup file back into the database, then reloads what is on screen. */
    fun importFrom(source: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importStatus = ImportStatus.Working) }
            val status = runCatching {
                val json = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(source)
                        ?.use { input -> input.readBytes().decodeToString() }
                        ?: error("Could not open the selected file")
                }
                repo.importJson(json)
            }.fold(
                onSuccess = { restored ->
                    if (restored.isEmpty) {
                        ImportStatus.Failed("That backup contained no accounts or transactions.")
                    } else {
                        ImportStatus.Done(
                            "Restored ${restored.transactions.size} transactions " +
                                "across ${restored.accounts.size} accounts.",
                        )
                    }
                },
                onFailure = { ImportStatus.Failed(it.message ?: it.javaClass.simpleName) },
            )
            _state.update { it.copy(importStatus = status) }
            if (status is ImportStatus.Done) loadLocal()
        }
    }

    fun clearData() {
        viewModelScope.launch {
            runCatching { repo.clearAllData() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { RootUiState(connections = emptyList(), manualRules = emptyList(), showRecurringOnly = true, showInternalTransfers = false, versionName = "", versionCode = 0) }
            _secretId.value = ""
            _secretKey.value = ""
            cachedInstitutions = null
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
            customTabs.launchUrl(appContext, link.toUri())
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
        val detectedRules = RecurringAnalyzer.analyze(all)
        val ignored = repo.ignoredRules.first()
        val manualRules = repo.manualRules.first()
        val accounts = repo.accounts()
        // Compile-time constants: no PackageManager lookup to fail and fall back to a fake
        // "1.0.0" / 0 that would then be compared against the latest release.
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        val manualRecurring = manualRules.mapNotNull { it.toRecurringRule() }
        val allRules = detectedRules + manualRecurring
        _state.update {
            it.copy(
                accounts = accounts,
                transactions = all,
                rules = detectedRules,
                manualRules = manualRules,
                ignoredRules = ignored,
                budget = BudgetEngine.snapshot(all, allRules.filter { rule -> rule.key !in ignored }, accounts),
                connections = repo.connections.first(),
                versionName = versionName,
                versionCode = versionCode,
            )
        }
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { RootViewModel(app) }
        }
    }
}