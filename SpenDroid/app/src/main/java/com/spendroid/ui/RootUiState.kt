package com.spendroid.ui

import android.net.Uri
import com.spendroid.data.Connection
import com.spendroid.data.SyncAllowance
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.RuleOverrideEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.InstitutionDto
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CardTiming
import com.spendroid.domain.Category
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.RecurringRule
import kotlinx.coroutines.flow.first

sealed interface ImportStatus {
    object Idle : ImportStatus
    object Working : ImportStatus
    data class Done(val message: String) : ImportStatus
    data class Failed(val message: String) : ImportStatus

    /** An encrypted backup: asked for its passphrase, then restored with [RootViewModel.importFrom]. */
    data class NeedsPassphrase(val source: Uri, val wrong: Boolean = false) : ImportStatus
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
    /** First-run setup finished, or skipped: the app opens with no bank link. */
    val setupDone: Boolean = false,
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
    /** Each account's allowance for a full sync, as the bank last reported it. */
    val syncAllowances: Map<String, com.spendroid.data.SyncAllowance.Account> = emptyMap(),
    /** When the next scheduled sync is due, epoch millis. */
    val nextSyncAt: Long? = null,
    /** What the last refresh did about the bank's allowance, in a sentence or two. */
    val syncNote: String? = null,
    /** Accounts whose last sync failed, and why. */
    val syncFailures: Map<String, com.spendroid.data.SyncFailure> = emptyMap(),
    /** Card payments read from notifications, newest first. */
    val seenSpends: List<com.spendroid.data.db.SeenSpendEntity> = emptyList(),
    /** The banking apps read, and the accounts each covers: what a payment's account can be. */
    val spendSources: List<com.spendroid.domain.NotificationSpend.Source> = emptyList(),
    /** "Always put this shop here", by [com.spendroid.domain.NotificationSpend.placeKey]. */
    val spendPlaces: Map<String, String> = emptyMap(),
    /** Each account with no bank link, and the last day an imported statement covers it. */
    val manualCoverage: Map<String, java.time.LocalDate?> = emptyMap(),
    /** Rows the bank has stopped listing, put to the user. */
    val duplicateQuestions: List<com.spendroid.domain.DuplicateCheck.Question> = emptyList(),
    /** The forecast to payday, any dip below zero ahead, and bills' price changes. */
    val outlook: com.spendroid.domain.Outlook = com.spendroid.domain.Outlook.EMPTY,
    /** New rows held out of the budget until a question about them is answered. */
    val heldDuplicateKeys: Set<String> = emptySet(),
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

/** The app's colours: its accent, and whether each account has its own. */
data class Look(
    val accent: com.spendroid.ui.theme.Accent = com.spendroid.ui.theme.Accent.GREEN,
    val accountColours: Boolean = true,
)
