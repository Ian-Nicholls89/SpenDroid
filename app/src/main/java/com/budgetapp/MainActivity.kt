package com.budgetapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budgetapp.ui.HomeScreen
import com.budgetapp.ui.OnboardingScreen
import com.budgetapp.ui.RecurringRulesScreen
import com.budgetapp.ui.RootViewModel
import com.budgetapp.ui.SettingsScreen
import com.budgetapp.ui.AccountManagementScreen
import com.budgetapp.ui.ManualRuleDialog
import com.budgetapp.ui.theme.BudgetTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RootViewModel by viewModels { RootViewModel.factory(application) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BudgetTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf("home") }
                var showManualDialog by remember { mutableStateOf(false) }
                if (state.hasCredentials) {
                    when (screen) {
                        "rules" -> RecurringRulesScreen(
                            rules = state.rules,
                            manualRules = state.manualRules,
                            ignored = state.ignoredRules,
                            onBack = { screen = "home" },
                            onToggle = viewModel::setRuleIgnored,
                            onAddManual = { showManualDialog = true },
                        )

                        "settings" -> SettingsScreen(
                            currentSecretId = viewModel.secretIdValue.collectAsStateWithLifecycle().value,
                            currentSecretKey = viewModel.secretKeyValue.collectAsStateWithLifecycle().value,
                            onBack = { screen = "home" },
                            onSave = viewModel::saveSecret,
                            onClearData = {
                                viewModel.clearData()
                                screen = "home"
                            },
                            onCheckUpdate = { /* handled in SettingsScreen */ },
                            currentGithubOwner = viewModel.githubOwnerValue.collectAsStateWithLifecycle().value,
                            currentGithubRepo = viewModel.githubRepoValue.collectAsStateWithLifecycle().value,
                            onSaveGithubConfig = viewModel::saveGithubConfig,
                        )

                        "accounts" -> AccountManagementScreen(
                            state = state,
                            onBack = { screen = "home" },
                            onLink = viewModel::link,
                            onRelink = viewModel::relink,
                            onUpdateAccount = viewModel::updateAccount,
                        )

                        else -> HomeScreen(
                            state = state,
                            onRefresh = viewModel::refresh,
                            onOpenRules = { screen = "rules" },
                            onRelink = viewModel::relink,
                            onLink = viewModel::link,
                            onOpenSettings = { screen = "settings" },
                            onOpenAccounts = { screen = "accounts" },
                            onToggleRecurring = viewModel::toggleRecurringOnly,
                            onToggleInternal = viewModel::toggleInternalTransfers,
                        )
                    }
                } else {
                    OnboardingScreen(
                        state = state,
                        onSaveSecret = viewModel::saveSecret,
                        onSearch = viewModel::loadInstitutions,
                        onLink = viewModel::link,
                    )
                }
                if (showManualDialog) {
                    ManualRuleDialog(
                        onDismiss = { showManualDialog = false },
                        onAdd = { payee, direction, amountMinor, currency, cadence, anchorDay, startDate ->
                            showManualDialog = false
                            viewModel.addManualRule(payee, direction, amountMinor, currency, cadence, anchorDay, startDate)
                        },
                        onAddFromCandidate = { candidate ->
                            showManualDialog = false
                            viewModel.addManualRuleFromCandidate(candidate)
                        },
                        viewModel = viewModel,
                    )
                }
            }
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}