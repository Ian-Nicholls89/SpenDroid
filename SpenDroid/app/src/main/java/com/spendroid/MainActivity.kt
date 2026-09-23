@file:OptIn(ExperimentalMaterial3Api::class)

package com.spendroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spendroid.ui.AccountManagementScreen
import com.spendroid.ui.HomeScreen
import com.spendroid.ui.LinkBankDialog
import com.spendroid.ui.ManualRuleDialog
import com.spendroid.ui.OnboardingScreen
import com.spendroid.ui.RecurringRulesScreen
import com.spendroid.ui.RootViewModel
import com.spendroid.ui.SettingsScreen
import com.spendroid.ui.TransactionsScreen
import com.spendroid.ui.theme.BudgetTheme

enum class AppScreen(
    val route: String,
    val title: String,
    /** Bottom-bar label: "Recurring rules" does not fit under an icon. */
    val shortTitle: String,
    val icon: ImageVector,
) {
    Home("home", "Dashboard", "Home", Icons.Filled.Home),
    Activity("activity", "Activity", "Activity", Icons.AutoMirrored.Filled.ReceiptLong),
    Accounts("accounts", "Accounts", "Accounts", Icons.Filled.AccountBalance),
    Rules("rules", "Recurring rules", "Rules", Icons.Filled.Repeat),
    Settings("settings", "Settings", "Settings", Icons.Filled.Settings);

    companion object {
        fun from(route: String): AppScreen = entries.firstOrNull { it.route == route } ?: Home
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: RootViewModel by viewModels { RootViewModel.factory(application) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BudgetTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var screenRoute by rememberSaveable { mutableStateOf(AppScreen.Home.route) }
                val screen = AppScreen.from(screenRoute)
                var showLinkDialog by remember { mutableStateOf(false) }
                var showManualDialog by remember { mutableStateOf(false) }

                val navigate: (AppScreen) -> Unit = { target -> screenRoute = target.route }

                if (state.hasCredentials) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = if (screen == AppScreen.Home) "SpenDroid" else screen.title,
                                        style = if (screen == AppScreen.Home) {
                                            MaterialTheme.typography.headlineSmall
                                        } else {
                                            MaterialTheme.typography.titleLarge
                                        },
                                    )
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        },
                        bottomBar = {
                            NavigationBar {
                                AppScreen.entries.forEach { destination ->
                                    NavigationBarItem(
                                        selected = destination == screen,
                                        onClick = { navigate(destination) },
                                        icon = {
                                            Icon(destination.icon, contentDescription = null)
                                        },
                                        label = { Text(destination.shortTitle) },
                                        alwaysShowLabel = false,
                                    )
                                }
                            }
                        },
                        floatingActionButton = {
                            if (screen == AppScreen.Home) {
                                ExtendedFloatingActionButton(
                                    onClick = { showLinkDialog = true },
                                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    text = { Text("Link bank") },
                                )
                            }
                        },
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            when (screen) {
                                AppScreen.Home -> HomeScreen(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    onRelink = viewModel::relink,
                                    onSeeAllTransactions = { navigate(AppScreen.Activity) },
                                    onLinkBank = { showLinkDialog = true },
                                )

                                AppScreen.Activity -> TransactionsScreen(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    onToggleRecurring = viewModel::toggleRecurringOnly,
                                    onToggleInternal = viewModel::toggleInternalTransfers,
                                )

                                AppScreen.Accounts -> AccountManagementScreen(
                                    state = state,
                                    onLink = viewModel::link,
                                    onRelink = viewModel::relink,
                                    onUpdateAccount = viewModel::updateAccount,
                                )

                                AppScreen.Rules -> RecurringRulesScreen(
                                    rules = state.rules,
                                    manualRules = state.manualRules,
                                    ignored = state.ignoredRules,
                                    onToggle = viewModel::setRuleIgnored,
                                    onAddManual = { showManualDialog = true },
                                )

                                AppScreen.Settings -> SettingsScreen(
                                    state = state,
                                    onSave = viewModel::saveSecret,
                                    onExport = viewModel::exportTo,
                                    onClearData = viewModel::clearData,
                                    onCheckUpdate = viewModel::checkForUpdate,
                                    secretId = viewModel.secretIdValue.collectAsStateWithLifecycle().value,
                                    secretKey = viewModel.secretKeyValue.collectAsStateWithLifecycle().value,
                                    notificationTime = viewModel.notificationTime.collectAsStateWithLifecycle().value,
                                    onSaveNotificationTime = viewModel::saveNotificationTime,
                                )
                            }
                        }
                    }
                } else {
                    OnboardingScreen(
                        state = state,
                        onSaveSecret = viewModel::saveSecret,
                        onSearch = viewModel::loadInstitutions,
                        onLink = viewModel::link,
                    )
                }

                if (showLinkDialog) {
                    LinkBankDialog(
                        state = state,
                        onDismiss = { showLinkDialog = false },
                        onLink = { institution ->
                            showLinkDialog = false
                            viewModel.link(institution)
                        },
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
