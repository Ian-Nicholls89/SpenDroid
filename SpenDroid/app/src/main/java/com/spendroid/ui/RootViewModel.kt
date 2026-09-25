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
import com.spendroid.data.ApkInstaller
import com.spendroid.data.UpdateChecker
import com.spendroid.data.GoCardlessRepository
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.RuleOverrideEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.InstitutionDto
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CardTiming
import com.spendroid.domain.PaymentShift
import com.spendroid.domain.WorkingDayCalendar
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.RecurringRule
import com.spendroid.domain.toRecurringRule
import com.spendroid.widget.refreshCardWidgets
import com.spendroid.widget.refreshWidgets
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
    /** Downloading the release the user asked for, 0-100. */
    data class Downloading(val percent: Int) : UpdateCheckStatus
    /** Android has been handed the package and is asking the user to confirm. */
    object Installing : UpdateCheckStatus
    /** The app may not install packages yet; the settings page is the way through. */
    object NeedsInstallPermission : UpdateCheckStatus
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
    val categoryRules: List<CategoryRuleEntity> = emptyList(),
    val budgetGoals: List<BudgetGoalEntity> = emptyList(),
    val transactionQuery: String = "",
    /** Id of the only account being shown, or null for all of them. */
    val accountFilter: String? = null,
    val categoryFilter: Category? = null,
    val primaryIncomeKey: String? = null,
    val budgetModel: BudgetModel = BudgetModel.FRESH_START,
    val cardTiming: CardTiming = CardTiming.AT_BILL,
    /** Regular payments marked as transfers once for every occurrence. */
    val transferGroups: Set<String> = emptySet(),
    /** "accountId|transactionId" of rows that arrived with the latest load, for a brief highlight. */
    val newTransactionKeys: Set<String> = emptySet(),
    /** Accounts whose last sync failed, and why. */
    val syncFailures: Map<String, com.spendroid.data.SyncFailure> = emptyMap(),
    /** How each payee has been filed by hand, for suggesting where a transaction belongs. */
    val categoryHistory: Map<String, Map<Category, Int>> = emptyMap(),
    val ruleOverrides: Map<String, RuleOverrideEntity> = emptyMap(),
    val bankHolidays: Set<java.time.LocalDate> = emptySet(),
    /** Name of today's bank holiday, when today is one. */
    val bankHolidayToday: String? = null,
    /** Step-by-step status while linking a bank. Not a failure. */
    val linkProgress: String? = null,
    /** Name of a bank just linked, while we ask whether another is wanted. */
    val justLinked: String? = null,
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
            // The time has changed, so every booking made for the old one is redone.
            DailyRoundupScheduler.schedule(getApplication(), reschedule = true)
        }
    }

    /**
     * Checks, and when the user asked for it, goes through with the update.
     *
     * A check the user started is a request to update; the weekly background check only
     * raises a notification and never downloads anything on its own.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Checking("Checking for updates…")) }

            val latest = try {
                UpdateChecker.latestRelease()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        updateCheckStatus = UpdateCheckStatus.Error(
                            "Error: ${e.message ?: e.javaClass.simpleName}",
                        ),
                    )
                }
                return@launch
            }

            if (latest == null) {
                _state.update {
                    it.copy(updateCheckStatus = UpdateCheckStatus.Error("Unable to check for updates"))
                }
                return@launch
            }
            if (!UpdateChecker.isNewer(latest)) {
                _state.update {
                    it.copy(
                        updateCheckStatus = UpdateCheckStatus.Success(
                            "You're up to date (v${latest.versionName}, build ${BuildConfig.VERSION_CODE})",
                        ),
                    )
                }
                return@launch
            }

            val context = getApplication<Application>()
            if (!ApkInstaller.canInstall(context)) {
                _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.NeedsInstallPermission) }
                return@launch
            }

            _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Downloading(0)) }
            val result = ApkInstaller.downloadAndInstall(context, latest) { percent ->
                _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Downloading(percent)) }
            }
            _state.update {
                it.copy(
                    updateCheckStatus = when (result) {
                        is ApkInstaller.Result.Started -> UpdateCheckStatus.Installing
                        ApkInstaller.Result.NeedsPermission -> UpdateCheckStatus.NeedsInstallPermission
                        is ApkInstaller.Result.Failed -> UpdateCheckStatus.Error(result.message)
                    },
                )
            }
        }
    }

    /** Opens the settings page where installing from this app is allowed. */
    fun openInstallPermissionSettings() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                ApkInstaller.permissionIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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
            doLink(institution, replacing = connection)
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

    /** Chooses which income sets the pay cycle; null returns to the largest-income guess. */
    fun setPrimaryIncome(key: String?) {
        viewModelScope.launch {
            repo.savePrimaryIncomeKey(key)
            loadLocal()
        }
    }

    /** Corrects a detected rule's day, or how it moves off a weekend or bank holiday. */
    fun setRuleOverride(
        ruleKey: String,
        anchorDay: Int?,
        shift: PaymentShift?,
        decemberAnchorDay: Int? = null,
    ) {
        viewModelScope.launch {
            repo.saveRuleOverride(
                RuleOverrideEntity(ruleKey, anchorDay, shift?.name, decemberAnchorDay),
            )
            loadLocal()
        }
    }

    fun setBudgetModel(model: BudgetModel) {
        viewModelScope.launch {
            repo.saveBudgetModel(model.name)
            loadLocal()
        }
    }

    fun setCardTiming(timing: CardTiming) {
        viewModelScope.launch {
            repo.saveCardTiming(timing.name)
            loadLocal()
            // The widget reads the same setting, and would otherwise disagree until the next sync.
            refreshWidgets(getApplication())
        }
    }

    fun setCategoryFilter(category: Category?) {
        _state.update { it.copy(categoryFilter = category) }
    }

    fun setAccountFilter(accountId: String?) {
        _state.update { it.copy(accountFilter = accountId) }
    }

    fun setTransactionQuery(query: String) {
        _state.update { it.copy(transactionQuery = query) }
    }

    /** A correction to one transaction, which every rule then defers to. */
    fun overrideCategory(tx: TransactionEntity, category: Category?) {
        viewModelScope.launch {
            repo.setCategoryOverride(tx.accountId, tx.transactionId, category?.name)
            loadLocal()
        }
    }

    /** Puts a transaction's category back exactly as it was before a swipe, override or none. */
    fun restoreCategory(tx: TransactionEntity, previousOverride: String?) {
        viewModelScope.launch {
            repo.setCategoryOverride(tx.accountId, tx.transactionId, previousOverride)
            loadLocal()
        }
    }

    /**
     * Turns a correction into a standing rule keyed on the payee, so the same merchant is
     * classified the same way next time instead of being re-guessed on every sync.
     */
    fun alwaysCategorise(tx: TransactionEntity, category: Category) {
        viewModelScope.launch {
            val pattern = tx.payee.trim().lowercase().takeIf { it.isNotBlank() } ?: return@launch
            repo.addCategoryRule(pattern, category.name)
            // Clear any one-off override so the new rule is what applies.
            repo.setCategoryOverride(tx.accountId, tx.transactionId, null)
            loadLocal()
        }
    }

    fun markAsTransfer(tx: TransactionEntity, isTransfer: Boolean) {
        viewModelScope.launch {
            repo.setInternalTransfer(tx.accountId, tx.transactionId, isTransfer)
            loadLocal()
        }
    }

    /** Marks every payment like this one - past and still to come - as a transfer, or undoes it. */
    fun markGroupAsTransfer(tx: TransactionEntity, isTransfer: Boolean) {
        viewModelScope.launch {
            repo.setTransferGroup(RecurringAnalyzer.groupKey(tx), isTransfer)
            loadLocal()
        }
    }

    fun markAsCardPayment(tx: TransactionEntity, isPayment: Boolean) {
        viewModelScope.launch {
            repo.setCardPayment(tx.accountId, tx.transactionId, isPayment)
            loadLocal()
        }
    }

    fun setBudgetGoal(category: Category, limitMinor: Long) {
        viewModelScope.launch {
            repo.setBudgetGoal(category.name, limitMinor)
            loadLocal()
        }
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

    /** Which balances this bank sent, and which is in use. For the account sheet. */
    fun balanceTypesFor(account: AccountEntity): List<Pair<String, Boolean>> =
        repo.balanceTypesFor(account)

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch {
            repo.updateAccount(account)
            loadLocal()
        }
    }

    /** [replacing] is the consent being renewed, when this is a reauthorisation. */
    private suspend fun doLink(institution: InstitutionDto, replacing: Connection? = null) {
        _state.update { it.copy(linkingBank = institution.name, error = null) }
        runCatching {
            _state.update { it.copy(linkProgress = "Contacting GoCardless…") }
            val req = repo.createRequisition(institution)
            _state.update { it.copy(linkProgress = "Opening ${institution.name}…") }
            val link = req.link ?: error("GoCardless did not return a link")
            val appContext = getApplication<Application>().applicationContext
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(appContext, link.toUri())
            _state.update { it.copy(linkProgress = "Waiting for you to approve access…") }
            val done = repo.pollUntilAuthorised(req.id ?: error("Missing requisition id"))
            _state.update { it.copy(linkProgress = "Approved. Fetching accounts…") }
            if (done.accounts.isEmpty()) error("No accounts returned by ${institution.name}. Status: ${done.status}")
            val connection = Connection(
                institutionId = institution.id,
                institutionName = institution.name,
                requisitionId = done.id ?: req.id!!,
                accountIds = done.accounts,
            )
            repo.saveConnection(connection, replacing)
            _state.update { it.copy(linkProgress = "Importing ${done.accounts.size} account(s)…") }
            done.accounts.forEach { repo.importAccount(institution.name, it) }
            // A renewed consent reissues every account under a new id; fold them back into
            // the accounts already here rather than counting them twice.
            repo.adoptPredecessors(connection)
        }
            .onSuccess {
                _state.update {
                    it.copy(
                        linkingBank = null,
                        error = null,
                        linkProgress = null,
                        // Most people have more than one account, and this is the moment they
                        // are already in the flow and have the bank's app to hand.
                        justLinked = institution.name,
                        reauthNeeded = it.reauthNeeded.filter { c ->
                            c.requisitionId != replacing?.requisitionId && c.institutionId != institution.id
                        },
                    )
                }
                loadLocal()
            }
            .onFailure { e ->
                // A DNS or connection failure is worth naming as such: the fix is to check
                // the connection and retry, not to pick a different bank.
                val message = when (e) {
                    is java.net.UnknownHostException ->
                        "Couldn't reach GoCardless. Check your connection and try again."
                    is java.io.IOException ->
                        e.message ?: "Network error while linking. Try again."
                    else -> "Link failed: ${e.message}"
                }
                _state.update { it.copy(linkingBank = null, linkProgress = null, error = message) }
            }
        _state.update { it.copy(linkingBank = null) }
    }

    fun dismissLinkPrompt() {
        _state.update { it.copy(justLinked = null) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            val reauth = mutableListOf<Connection>()
            var offline = false
            var imported = false
            var limited = false
            // PSD2 rations unattended calls to about four per account per day and the nightly
            // sync spends one, so an account pulled within the hour is not pulled again.
            val recent = repo.accounts()
                .filter { System.currentTimeMillis() - it.lastSynced < MIN_MANUAL_RESYNC_MS }
                .map { it.id }
                .toSet()

            /**
             * Only a refusal from the bank means the consent needs renewing. A dropped
             * connection used to be reported the same way, sending people to reauthorise a
             * bank that was fine.
             */
            fun needsReauth(e: Throwable): Boolean {
                if (e is java.io.IOException) offline = true
                if (e is retrofit2.HttpException && e.code() == 429) limited = true
                return e is retrofit2.HttpException && e.code() in REAUTH_CODES
            }

            runCatching {
                repo.connections.first().forEach { connection ->
                    if (connection.accountIds.isNotEmpty()) {
                        connection.accountIds.filter { it !in recent }.forEach { id ->
                            runCatching { repo.syncAccount(connection.institutionName, id) }
                                .onSuccess { imported = true }
                                .onFailure { e -> if (needsReauth(e) && connection !in reauth) reauth += connection }
                        }
                    } else {
                        val req = runCatching { repo.requisition(connection.requisitionId) }
                            .onFailure { e -> if (needsReauth(e)) reauth += connection }
                            .getOrNull()
                        if (req != null && req.status in setOf("SA", "LN") && req.accounts.isNotEmpty()) {
                            repo.saveConnection(connection.copy(accountIds = req.accounts))
                            req.accounts.forEach { id ->
                                runCatching { repo.syncAccount(connection.institutionName, id) }
                                    .onSuccess { imported = true }
                                    .onFailure { e -> if (needsReauth(e) && connection !in reauth) reauth += connection }
                            }
                        } else if (req != null && req.status in setOf("EX", "RJ")) {
                            reauth += connection
                        }
                    }
                }
            }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            // Every account was synced recently, so nothing re-ran detection. It is local and
            // free, so run it anyway: the figures should always reflect the current rules.
            if (!imported) runCatching { repo.reanalyze() }
            _state.update {
                it.copy(
                    reauthNeeded = reauth,
                    error = it.error ?: when {
                        // The bank allows about four syncs a day and three are scheduled, so a
                        // second manual refresh can meet the limit. It resets on the bank's clock.
                        limited -> "Your bank's daily limit is used up. The next scheduled sync will catch up."
                        offline -> "Couldn't reach your bank. Check your connection and try again."
                        else -> null
                    },
                )
            }
            loadLocal()
            refreshWidgets(getApplication())
            refreshCardWidgets(getApplication())
            _state.update { it.copy(syncing = false) }
        }
    }

    private suspend fun loadLocal() {
        val all = repo.transactions()
        // What this load brought that the last one did not. Empty on the first load, which is
        // everything and so nothing worth pointing at.
        val before = _state.value.transactions
        val newKeys = if (before.isEmpty()) {
            emptySet()
        } else {
            val seen = before.mapTo(HashSet()) { "${it.accountId}|${it.transactionId}" }
            all.map { "${it.accountId}|${it.transactionId}" }.filterNot { it in seen }.toSet()
        }
        val detectedRules = RecurringAnalyzer.analyze(all)
        val ignored = repo.ignoredRules.first()
        val manualRules = repo.manualRules.first()
        val accounts = repo.accounts()
        val categoryRules = repo.categoryRules.first()
        val primaryIncomeKey = repo.primaryIncomeKey.first()
        val overrides = repo.ruleOverrides.first().associateBy { it.ruleKey }
        // Refreshes itself only when the stored run of holidays is nearly spent.
        val holidays = runCatching { repo.bankHolidays() }.getOrDefault(emptyMap())
        val budgetModel = BudgetModel.from(repo.budgetModel.first())
        val cardTiming = CardTiming.from(repo.cardTiming.first())
        // The day the figures are for, which is yesterday until this morning's first sync.
        val budgetTime = repo.budgetTime()
        val budgetGoals = repo.budgetGoals.first()
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
                categoryRules = categoryRules,
                budgetGoals = budgetGoals,
                budget = BudgetEngine.snapshot(
                    referenceTime = budgetTime,
                    transactions = all,
                    rules = allRules.filter { rule -> rule.key !in ignored },
                    accounts = accounts,
                    primaryIncomeKey = primaryIncomeKey,
                    budgetModel = budgetModel,
                    calendar = WorkingDayCalendar(holidays.keys),
                    overrides = overrides,
                    cardTiming = cardTiming,
                ),
                ruleOverrides = overrides,
                bankHolidays = holidays.keys,
                bankHolidayToday = holidays[java.time.LocalDate.now()],
                primaryIncomeKey = primaryIncomeKey,
                budgetModel = budgetModel,
                cardTiming = cardTiming,
                transferGroups = repo.transferGroups.first(),
                newTransactionKeys = newKeys,
                syncFailures = repo.syncFailures.first(),
                categoryHistory = CategoryEngine.history(all),
                connections = repo.connections.first(),
                versionName = versionName,
                versionCode = versionCode,
            )
        }
    }

    companion object {
        private val MIN_MANUAL_RESYNC_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(1)

        /** Refusals that mean the bank's consent has lapsed or been withdrawn. */
        private val REAUTH_CODES = setOf(401, 403, 409)

        fun factory(app: Application) = viewModelFactory {
            initializer { RootViewModel(app) }
        }
    }
}