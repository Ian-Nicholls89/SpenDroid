package com.budgetapp.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.budgetapp.BudgetApplication
import com.budgetapp.data.Connection
import com.budgetapp.data.GoCardlessRepository
import com.budgetapp.data.db.AccountEntity
import com.budgetapp.data.db.ManualRecurringRuleEntity
import com.budgetapp.data.db.TransactionEntity
import com.budgetapp.data.remote.InstitutionDto
import com.budgetapp.domain.BudgetEngine
import com.budgetapp.domain.BudgetSnapshot
import com.budgetapp.domain.RecurringAnalyzer
import com.budgetapp.domain.RecurringRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

class RootViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: GoCardlessRepository = (app as BudgetApplication).repository

    private val _state = MutableStateFlow(RootUiState(connections = emptyList(), manualRules = emptyList(), showRecurringOnly = true, showInternalTransfers = false))
    val state: StateFlow<RootUiState> = _state.asStateFlow()

    private val _secretId = MutableStateFlow("")
    val secretIdValue: StateFlow<String> = _secretId.asStateFlow()

    private val _secretKey = MutableStateFlow("")
    val secretKeyValue: StateFlow<String> = _secretKey.asStateFlow()

    private val _githubOwner = MutableStateFlow("")
    val githubOwnerValue: StateFlow<String> = _githubOwner.asStateFlow()

    private val _githubRepo = MutableStateFlow("")
    val githubRepoValue: StateFlow<String> = _githubRepo.asStateFlow()

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
        viewModelScope.launch {
            repo.getGithubConfig().collect { config ->
                _githubOwner.value = config.first
                _githubRepo.value = config.second
            }
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

    fun saveGithubConfig(owner: String, repo: String) {
        viewModelScope.launch {
            runCatching { this@RootViewModel.repo.saveGithubConfig(owner.trim(), repo.trim()) }
                .onSuccess { _githubOwner.value = owner.trim(); _githubRepo.value = repo.trim() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
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