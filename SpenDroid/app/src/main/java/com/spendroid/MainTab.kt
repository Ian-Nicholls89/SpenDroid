package com.spendroid

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spendroid.ui.AccountManagementScreen
import com.spendroid.ui.CardAlertsSection
import com.spendroid.ui.HomeScreen
import com.spendroid.ui.RecurringRulesScreen
import com.spendroid.ui.RootViewModel
import com.spendroid.ui.SettingsScreen
import com.spendroid.ui.SpendingScreen

/**
 * What each tab at the foot shows, and where each screen's actions go. The screen's shared state
 * comes in as it is - a dialog to open, a sheet, a tab - so what's set here is set for all.
 */
@Composable
internal fun MainTab(
    shown: AppScreen,
    state: com.spendroid.ui.RootUiState,
    viewModel: RootViewModel,
    look: com.spendroid.ui.Look,
    navigate: (AppScreen) -> Unit,
    news: com.spendroid.ui.PennyNews.News?,
    questions: List<com.spendroid.ui.PennyQuestion>,
    linkDialog: MutableState<Boolean>,
    forecastState: MutableState<String?>,
    todayState: MutableState<Boolean>,
    askingState: MutableState<Boolean>,
    spendingTabState: MutableIntState,
    manualDialog: MutableState<Boolean>,
    checkBalance: MutableState<String?>,
    importRequest: MutableState<com.spendroid.ui.ImportRequest?>,
    openRule: MutableState<String?>,
    authenticate: (String, (Boolean) -> Unit) -> Unit,
    applyHideInRecents: (Boolean) -> Unit,
) {
    var showLinkDialog by linkDialog
    var forecastFor by forecastState
    var showToday by todayState
    var asking by askingState
    var spendingTab by spendingTabState
    var showManualDialog by manualDialog
    when (shown) {
        AppScreen.Home -> HomeScreen(
            state = state,
            onRefresh = viewModel::refresh,
            onRelink = viewModel::relink,
            onSeeAllTransactions = { navigate(AppScreen.Spending) },
            onSetBudgetGoal = viewModel::setBudgetGoal,
            onLinkBank = { showLinkDialog = true },
            onSeeAccount = { id ->
                viewModel.setAccountFilter(id)
                navigate(AppScreen.Spending)
            },
            onOpenForecast = { id -> forecastFor = id ?: "" },
            onReload = viewModel::reloadLocal,
            onOpenToday = { showToday = true },
            news = news,
            onNews = {},
            prompts = {
                // Accounts with no bank link: how current the figures are.
                com.spendroid.ui.ManualFreshness(
                    state,
                    onCheck = { checkBalance.value = it },
                    onImport = { importRequest.value = com.spendroid.ui.ImportRequest(accountId = it) },
                )
                // Penny's questions, put off: the way back to them.
                com.spendroid.ui.PennyQuestionsStrip(questions.size, onOpen = { asking = true })
            },
        )

        AppScreen.Spending -> SpendingScreen(
            state = state,
            onRefresh = viewModel::refresh,
            onToggleRecurring = viewModel::toggleRecurringOnly,
            onToggleInternal = viewModel::toggleInternalTransfers,
            onQueryChange = viewModel::setTransactionQuery,
            onAccountFilter = viewModel::setAccountFilter,
            onOverrideCategory = viewModel::overrideCategory,
            onAlwaysCategorise = viewModel::alwaysCategorise,
            onMarkTransfer = viewModel::markAsTransfer,
            onMarkTransferGroup = viewModel::markGroupAsTransfer,
            onRestoreCategory = viewModel::restoreCategory,
            onMarkCardPayment = viewModel::markAsCardPayment,
            onCategoryFilter = viewModel::setCategoryFilter,
            onSetBudgetGoal = viewModel::setBudgetGoal,
            onTreatAsBill = viewModel::treatAsBill,
            onAssignSeen = { id, account, always -> viewModel.assignSeenSpend(id, account, always) },
            onAnswerRepeat = viewModel::answerRepeat,
            onCardCheck = viewModel::answerCardCheck,
            tabIndex = spendingTab,
            onTabIndex = { spendingTab = it },
            prompts = {
                com.spendroid.ui.PennyQuestionsStrip(
                    questions.size,
                    onOpen = { asking = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            },
        )

        AppScreen.Accounts -> AccountManagementScreen(
            state = state,
            onLink = viewModel::link,
            onRelink = viewModel::relink,
            onUpdateAccount = viewModel::updateAccount,
            balanceTypesFor = viewModel::balanceTypesFor,
            onSeeTransactions = { account ->
                viewModel.setAccountFilter(account.id)
                navigate(AppScreen.Spending)
            },
            onUndoImport = viewModel::undoImport,
            onMoveImport = viewModel::moveImport,
            onImport = { accountId -> importRequest.value = com.spendroid.ui.ImportRequest(accountId = accountId) },
            onAddManual = viewModel::addManualAccount,
            onRemoveManual = { viewModel.removeManualAccount(it.id) },
            onCheckBalance = { checkBalance.value = it.id },
        )

        AppScreen.Rules -> RecurringRulesScreen(
            rules = state.rules,
            manualRules = state.manualRules,
            ignored = state.ignoredRules,
            onToggle = viewModel::setRuleIgnored,
            onAddManual = { showManualDialog = true },
            primaryIncomeKey = state.primaryIncomeKey,
            onSetPrimaryIncome = viewModel::setPrimaryIncome,
            budget = state.budget,
            overrides = state.ruleOverrides,
            onSetOverride = viewModel::setRuleOverride,
            holidays = state.bankHolidays,
            accounts = state.accounts,
            onSetManualPaidFrom = viewModel::setManualRulePaidFrom,
            onDeleteManual = viewModel::deleteManualRule,
            priceChanges = state.outlook.priceChanges,
            openRuleKey = openRule.value,
            onOpenedRule = { openRule.value = null },
            subscriptions = state.outlook.subscriptions,
            contracts = state.outlook.contracts,
            transactions = state.transactions,
            onReload = viewModel::reloadLocal,
        )

        AppScreen.Settings -> SettingsScreen(
            state = state,
            onSave = viewModel::saveSecret,
            onSetBudgetModel = viewModel::setBudgetModel,
            onSetCardTiming = viewModel::setCardTiming,
            onExport = viewModel::exportTo,
            onImport = { viewModel.importFrom(it) },
            onClearData = viewModel::clearData,
            onUnlockBackup = viewModel::importFrom,
            onCancelRestore = viewModel::cancelRestore,
            onCheckUpdate = viewModel::checkForUpdate,
            onOpenInstallSettings = viewModel::openInstallPermissionSettings,
            secretId = viewModel.secretIdValue.collectAsStateWithLifecycle().value,
            secretKey = viewModel.secretKeyValue.collectAsStateWithLifecycle().value,
            notificationTime = viewModel.notificationTime.collectAsStateWithLifecycle().value,
            onSaveNotificationTime = viewModel::saveNotificationTime,
            look = look,
            onSetAccent = viewModel::setAccent,
            onSetAccountColours = viewModel::setAccountColours,
            onSetIconFollows = viewModel::setIconFollows,
            onConfirmLock = { done -> authenticate("Confirm it's you") { ok -> done(ok); if (ok) com.spendroid.data.Privacy.unlocked = true } },
            onHideInRecents = applyHideInRecents,
            onReload = viewModel::reloadLocal,
            cardAlerts = {
                CardAlertsSection(
                    accounts = state.accounts,
                    sources = viewModel.spendSources.collectAsStateWithLifecycle().value,
                    readingOn = viewModel.spendReadingOn.collectAsStateWithLifecycle().value,
                    samples = viewModel.notificationSamples.collectAsStateWithLifecycle().value,
                    onSaveSources = viewModel::saveSpendSources,
                    onReadingOn = viewModel::setSpendReadingOn,
                    onUpdateAccount = viewModel::updateAccount,
                    onClear = viewModel::clearNotificationData,
                    learned = viewModel.spendLearned.collectAsStateWithLifecycle().value,
                    onTeach = viewModel::teachNotification,
                    onForgetLearned = viewModel::forgetLearnedNotifications,
                    fate = { com.spendroid.ui.notificationFate(it, state) },
                )
            },
        )
    }
}
