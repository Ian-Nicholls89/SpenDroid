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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.spendroid.ui.theme.BudgetTheme
import kotlinx.coroutines.launch

enum class AppScreen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    Home("home", "Dashboard", Icons.Filled.Home),
    Accounts("accounts", "Accounts", Icons.Filled.AccountCircle),
    Rules("rules", "Recurring rules", Icons.AutoMirrored.Filled.List),
    Settings("settings", "Settings", Icons.Filled.Settings);

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

                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()
                val openDrawer: () -> Unit = { drawerScope.launch { drawerState.open() } }
                val navigate: (AppScreen) -> Unit = { target ->
                    screenRoute = target.route
                    drawerScope.launch { drawerState.close() }
                }

                if (state.hasCredentials) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            SpenDroidDrawer(
                                currentScreen = screen,
                                versionName = state.versionName,
                                onNavigate = navigate,
                            )
                        },
                    ) {
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
                                    navigationIcon = {
                                        IconButton(onClick = openDrawer) {
                                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
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
                                        onToggleRecurring = viewModel::toggleRecurringOnly,
                                        onToggleInternal = viewModel::toggleInternalTransfers,
                                        onLinkBank = { showLinkDialog = true },
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

@Composable
private fun SpenDroidDrawer(
    currentScreen: AppScreen,
    versionName: String,
    onNavigate: (AppScreen) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "SpenDroid",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (versionName.isBlank()) "Budget tracker" else "v$versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            AppScreen.entries.forEach { screen ->
                NavigationDrawerItem(
                    label = { Text(screen.title) },
                    icon = { Icon(screen.icon, contentDescription = null) },
                    selected = screen == currentScreen,
                    onClick = { onNavigate(screen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Divider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = "SpenDroid · GoCardless budgeting",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
    }
}