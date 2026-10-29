package com.spendroid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spendroid.BudgetApplication
import com.spendroid.BuildConfig
import com.spendroid.data.Connection
import com.spendroid.data.SyncAllowance
import com.spendroid.data.GoCardlessRepository
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.RuleOverrideEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.InstitutionDto
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CardTiming
import com.spendroid.domain.PaymentShift
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.RecurringAnalyzer
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted

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

    // Areas in files of their own, before init: its first refresh lists the banks.
    private val host = AreaHost(app, repo, _state, viewModelScope, loadLocal = { loadLocal() }, refreshConfig = { refreshConfig() })
    private val updates = UpdateArea(host)
    private val bankLink = BankLinkArea(host)
    private val setup = SetupArea(host)
    private val backups = BackupArea(host, forgetLink = {
        _secretId.value = ""
        _secretKey.value = ""
        bankLink.forgetInstitutions()
    })

    fun finishSetup() = setup.finishSetup()
    fun startFromToday(name: String, bank: String, balanceMinor: Long, payMinor: Long, cadence: com.spendroid.domain.Cadence, anchorDay: Int, lastPaid: java.time.LocalDate) =
        setup.startFromToday(name, bank, balanceMinor, payMinor, cadence, anchorDay, lastPaid)
    fun addPay(payMinor: Long, cadence: com.spendroid.domain.Cadence, anchorDay: Int, lastPaid: java.time.LocalDate, accountId: String?) =
        setup.addPay(payMinor, cadence, anchorDay, lastPaid, accountId)
    fun addManualAccount(label: String, bank: String, type: com.spendroid.data.db.AccountType, balanceMinor: Long?) =
        setup.addManualAccount(label, bank, type, balanceMinor)
    fun removeManualAccount(accountId: String) = setup.removeManualAccount(accountId)
    fun setManualBalance(accountId: String, balanceMinor: Long) = setup.setManualBalance(accountId, balanceMinor)
    fun applyBalanceCheck(accountId: String, typedMinor: Long, moveSpendId: String?) = setup.applyBalanceCheck(accountId, typedMinor, moveSpendId)
    fun checkForUpdate() = updates.checkForUpdate()
    fun openInstallPermissionSettings() = updates.openInstallPermissionSettings()
    fun installDownloadedUpdate() = updates.installDownloaded()
    fun loadInstitutions(query: String) = bankLink.loadInstitutions(query)
    fun link(institution: InstitutionDto) = bankLink.link(institution)
    fun relink(connection: Connection) = bankLink.relink(connection)
    fun dismissLinkPrompt() = bankLink.dismissLinkPrompt()
    fun exportTo(destination: Uri) = backups.exportTo(destination)
    fun importFrom(source: Uri, passphrase: CharArray? = null) = backups.importFrom(source, passphrase)
    fun cancelRestore() = backups.cancelRestore()
    fun clearData() = backups.clearData()

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
        val setupDone = setupPrefs().getBoolean("done", false)
        _state.update { it.copy(hasCredentials = hasCredentials, setupDone = setupDone) }
        if (hasCredentials) loadInstitutions("")
        // With or without a bank link: setup reads what's imported as it goes.
        // Merges any purchase counted more than once from notifications, straight away.
        runCatching { repo.reconcileSeenSpends() }
        // Rules made from a pending name with the card's digits match the shop's name instead.
        runCatching { repo.tidyCardDigitRules() }
        loadLocal()
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

    fun setRuleIgnored(key: String, ignored: Boolean) {
        // Brought back by hand: a bill after all, so a "just spending" said to Penny no longer holds.
        if (!ignored) _state.value.rules.firstOrNull { it.key == key }?.let { PennyBills.answer(getApplication(), it, isBill = true) }
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

    /** The look the user chose, read straight from storage so the first frame already has it. */
    val look: kotlinx.coroutines.flow.StateFlow<Look> = kotlinx.coroutines.flow.combine(repo.accent, repo.accountColours) { accent, colours ->
        Look(com.spendroid.ui.theme.Accent.from(accent), colours)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, Look())

    fun setAccent(accent: com.spendroid.ui.theme.Accent) {
        viewModelScope.launch { repo.saveAccent(accent.name) }
        // The launcher icon too, if the user asked it to follow.
        if (LauncherIcon.follows(getApplication())) runCatching { LauncherIcon.apply(getApplication(), accent) }
    }

    fun setIconFollows(follows: Boolean) {
        runCatching { LauncherIcon.setFollows(getApplication(), follows, look.value.accent) }
    }

    fun setAccountColours(on: Boolean) {
        viewModelScope.launch { repo.saveAccountColours(on) }
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

    // --- Spending read from notifications ---

    val spendSources = repo.spendSources
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    val spendReadingOn = repo.spendReadingOn
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), true)
    val notificationSamples = repo.notificationSamples
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // A spend read while the app is open shows at once, not at the next reload.
        viewModelScope.launch {
            // Once a burst is over: a sync hands several over in a row, and each redid everything.
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            repo.seenSpends.drop(1).debounce(400).collect { loadLocal() }
        }
    }

    fun saveSpendSources(sources: List<com.spendroid.domain.NotificationSpend.Source>) {
        viewModelScope.launch { repo.saveSpendSources(sources) }
    }

    fun setSpendReadingOn(on: Boolean) {
        viewModelScope.launch {
            repo.setSpendReadingOn(on)
            loadLocal()
        }
    }

    fun clearNotificationData() {
        viewModelScope.launch { repo.clearNotificationData() }
    }

    fun assignSeenSpend(id: String, accountId: String) = assignSeenSpend(id, accountId, always = false)

    /** Which account a payment read from a notification came from; [always] for every one at that shop. */
    fun assignSeenSpend(id: String, accountId: String, always: Boolean) {
        com.spendroid.work.SpendQuestions.answered(getApplication(), id)
        viewModelScope.launch { repo.assignSeenSpend(id, accountId, always) }
    }

    /** Where "always put this shop here" sends payments, by [com.spendroid.domain.NotificationSpend.placeKey]. */
    val spendPlaces = repo.spendPlaces
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The last pay cycle looked back on: worked out off the main thread, as it takes a moment. */
    suspend fun lastCycleReview(): com.spendroid.domain.CycleReview? =
        withContext(Dispatchers.Default) { runCatching { repo.lastCycleReview() }.getOrNull() }

    // --- First-run setup ---

    private fun setupPrefs() = getApplication<Application>().getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, android.content.Context.MODE_PRIVATE)

    // --- Accounts with no bank link ---

    suspend fun previewBalanceCheck(accountId: String, typedMinor: Long) = repo.previewBalanceCheck(accountId, typedMinor)

    fun dismissSeenSpend(id: String) {
        com.spendroid.work.SpendQuestions.answered(getApplication(), id)
        viewModelScope.launch { repo.dismissSeenSpend(id) }
    }

    /** Penny's "once, or twice?" answered: [once] drops [spendId] as a repeat of [twinId]. */
    fun answerRepeat(spendId: String, twinId: String, once: Boolean) {
        com.spendroid.work.SpendQuestions.repeatAnswered(getApplication(), spendId)
        viewModelScope.launch {
            repo.answerRepeat(spendId, twinId, once)
            loadLocal()
        }
    }

    fun answerCardCheck(id: String, check: Boolean) {
        if (check) com.spendroid.work.SpendQuestions.answered(getApplication(), id)
        viewModelScope.launch { repo.answerCardCheck(id, check) }
    }

    fun keepSeenSpend(id: String) {
        viewModelScope.launch { repo.keepSeenSpend(id) }
    }

    fun confirmDuplicate(question: com.spendroid.domain.DuplicateCheck.Question) {
        viewModelScope.launch {
            repo.confirmDuplicate(question)
            loadLocal()
        }
    }

    fun keepVanished(question: com.spendroid.domain.DuplicateCheck.Question) {
        viewModelScope.launch {
            repo.keepVanished(question)
            loadLocal()
        }
    }

    fun removeVanished(question: com.spendroid.domain.DuplicateCheck.Question) {
        viewModelScope.launch {
            repo.removeVanished(question)
            loadLocal()
        }
    }

    /** Undoes one import from the computer: every row it added. */
    fun undoImport(batch: com.spendroid.web.WebImport.Batch) {
        viewModelScope.launch {
            com.spendroid.web.WebImport.undo(getApplication(), batch)
            loadLocal()
        }
    }

    /** Moves one import to the account it was meant for; [done] hears what happened. */
    fun moveImport(batch: com.spendroid.web.WebImport.Batch, toAccountId: String, swap: Boolean, done: (com.spendroid.web.WebImport.Check) -> Unit) {
        viewModelScope.launch {
            val result = com.spendroid.web.WebImport.move(getApplication(), batch, toAccountId, swap)
            loadLocal()
            done(result)
        }
    }

    /** Shows what changed elsewhere - an import allowed from the notification. */
    fun reloadLocal() {
        viewModelScope.launch { loadLocal() }
    }

    fun setManualRulePaidFrom(rule: ManualRecurringRuleEntity, accountId: String?) {
        viewModelScope.launch {
            repo.setManualRulePaidFrom(rule, accountId)
            loadLocal()
        }
    }

    fun deleteManualRule(rule: ManualRecurringRuleEntity) {
        viewModelScope.launch {
            repo.deleteManualRule(rule.id)
            loadLocal()
        }
    }

    fun treatAsBill(tx: TransactionEntity, cadence: com.spendroid.domain.Cadence) {
        viewModelScope.launch {
            repo.treatAsBill(tx, cadence)
            loadLocal()
        }
    }

    fun teachNotification(sampleId: Long, count: Boolean) {
        viewModelScope.launch {
            val ok = repo.teachNotification(sampleId, count)
            if (!ok) _state.update { it.copy(error = "That notification has no single amount to learn from.") }
            loadLocal()
        }
    }

    fun forgetLearnedNotifications() {
        viewModelScope.launch { repo.forgetLearned() }
    }

    val spendLearned = repo.spendLearned
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleInternalTransfers() {
        _state.update { it.copy(showInternalTransfers = !it.showInternalTransfers) }
    }

    fun addManualRule(payee: String, direction: String, amountMinor: Long, currency: String, cadence: String, anchorDay: Int, startDate: String, accountId: String? = null) {
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
                accountId = accountId,
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
            accountId = candidate.sampleTransactions.maxByOrNull { it.bookingDate }?.accountId,
        )
    }

    suspend fun getRecurringCandidates(): List<RecurringAnalyzer.RecurringCandidate> =
        withContext(Dispatchers.Default) { RecurringAnalyzer.findCandidates(repo.transactions()) }

    /** Which balances this bank sent, and which is in use. For the account sheet. */
    fun balanceTypesFor(account: AccountEntity): List<Pair<String, Boolean>> =
        repo.balanceTypesFor(account)

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch {
            repo.updateAccount(account)
            loadLocal()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null, syncNote = null) }
            val reauth = mutableListOf<Connection>()
            val now = System.currentTimeMillis()
            val labels = repo.accounts().associate { it.id to it.label }
            // Accounts the bank would refuse are not asked: a refusal costs nothing against the
            // account, but it tells you nothing either, and it counts against GoCardless's own limit.
            val spent = mutableMapOf<String, Long>()
            suspend fun allowed(id: String): Boolean {
                val allowance = repo.allowanceFor(id, now)
                if (!SyncAllowance.exhausted(allowance, now)) return true
                spent[id] = allowance?.resetAt ?: now
                return false
            }
            val synced = mutableListOf<String>()
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
                        connection.accountIds.filter { it !in recent }.filter { allowed(it) }.forEach { id ->
                            runCatching { repo.syncAccount(connection.institutionName, id) }
                                .onSuccess { imported = true; synced += id }
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
            // Say what the allowance meant for this refresh: what was not asked, and anything
            // that has just used its last sync before the bank's reset.
            val notes = buildList {
                spent.forEach { (id, reset) ->
                    add("Refresh unavailable for ${labels[id] ?: "an account"} · resets in ${SyncAllowance.countdown(reset, now)}")
                }
                synced.forEach { id ->
                    val after = repo.allowanceFor(id)
                    if (after != null && after.remaining <= 0 && after.resetAt != null) {
                        add("That was ${labels[id] ?: "an account"}'s last refresh · resets in ${SyncAllowance.countdown(after.resetAt, now)}")
                    }
                }
                if (spent.isEmpty() && synced.isEmpty() && recent.isNotEmpty() && !limited && !offline) {
                    add("Everything synced within the last hour, so the bank was not asked again")
                }
            }
            if (notes.isNotEmpty()) _state.update { it.copy(syncNote = notes.joinToString("\n")) }
            if (spent.isNotEmpty() || limited) runCatching { DailyRoundupScheduler.bookCatchUp(getApplication()) }
            // Every account was synced recently, so nothing re-ran detection. It is local and
            // free, so run it anyway: the figures should always reflect the current rules.
            if (!imported) runCatching { repo.reanalyze() }
            _state.update {
                it.copy(
                    reauthNeeded = reauth,
                    error = it.error ?: when {
                        // The bank allows about four syncs a day and two are scheduled, so a
                        // second manual refresh can meet the limit. It resets on the bank's clock.
                        limited -> "Your bank's daily limit is used up. The next scheduled sync will catch up."
                        offline -> "Couldn't reach your bank. Check your connection and try again."
                        else -> null
                    },
                )
            }
            loadLocal()
            refreshWidgets(getApplication())
            _state.update { it.copy(syncing = false) }
        }
    }

    /**
     * Reloads everything on screen. The analysis - recurring detection, the budget, the card
     * cycles, category history - is real work over the whole history, and it ran on the main
     * thread, where it held up drawing as the history grew.
     */
    private suspend fun loadLocal() = withContext(Dispatchers.Default) {
        // Payee names, contracts and subscriptions in first: no row shows a bank's name for a moment.
        com.spendroid.data.Choices.awaitLoaded()
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
        // The same calculation the widgets and the watch show.
        val budgetBefore = _state.value.budget
        val budget = repo.budgetSnapshotOf(all, budgetTime)
        val questions = com.spendroid.domain.DuplicateCheck.questions(repo.duplicateChecks(), all)
        val outlook = runCatching {
            repo.outlook(
                budget, all, com.spendroid.data.MoneyWatch(getApplication()).includeVariable,
                com.spendroid.data.SubscriptionChoices(getApplication()).kinds(),
                com.spendroid.data.ContractEnds(getApplication()).all(),
                com.spendroid.data.ContractEnds(getApplication()).accepted(),
            )
        }.getOrDefault(com.spendroid.domain.Outlook.EMPTY)
        val renewals = runCatching {
            com.spendroid.domain.Renewals.all(com.spendroid.data.RenewalStore(getApplication()).all(), all, java.time.LocalDate.now())
        }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                renewals = renewals,
                accounts = accounts,
                transactions = all,
                rules = detectedRules,
                manualRules = manualRules,
                ignoredRules = ignored,
                categoryRules = categoryRules,
                budgetGoals = budgetGoals,
                budget = budget,
                ruleOverrides = overrides,
                bankHolidays = holidays.keys,
                bankHolidayToday = holidays[java.time.LocalDate.now()],
                primaryIncomeKey = primaryIncomeKey,
                budgetModel = budgetModel,
                cardTiming = cardTiming,
                transferGroups = repo.transferGroups.first(),
                newTransactionKeys = newKeys,
                syncAllowances = accounts
                    .mapNotNull { account -> repo.allowanceFor(account.id)?.let { account.id to it } }
                    .toMap(),
                nextSyncAt = nextScheduledSync(),
                syncFailures = repo.syncFailures.first(),
                seenSpends = repo.seenSpends.first(),
                spendSources = repo.spendSources.first(),
                spendPlaces = repo.spendPlaces.first(),
                manualCoverage = repo.manualCoverage(),
                duplicateQuestions = questions,
                outlook = outlook,
                heldDuplicateKeys = repo.heldDuplicateKeys(),
                categoryHistory = CategoryEngine.history(all),
                connections = repo.connections.first(),
                versionName = versionName,
                versionCode = versionCode,
            )
        }
        // Whatever changed the figures - an answer to Penny, a transfer marked, a bill ignored, an
        // import - the widgets and the watch show them too. Before, only a sync or a notification
        // redrew them, and they disagreed with the app until the next one.
        if (budgetBefore != null && budget != budgetBefore) viewModelScope.launch {
            refreshWidgets(getApplication())
        }
    }

    /** The next of the day's two sync slots, from the notification time in Settings. */
    private suspend fun nextScheduledSync(): Long {
        val notification = runCatching { java.time.LocalTime.parse(repo.notificationTime.first()) }
            .getOrDefault(java.time.LocalTime.of(21, 0))
        val now = java.time.ZonedDateTime.now()
        return DailyRoundupScheduler.syncTimes(notification)
            .map { slot -> now.plus(DailyRoundupScheduler.delayUntilNext(now, slot)) }
            .minOf { it.toInstant().toEpochMilli() }
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