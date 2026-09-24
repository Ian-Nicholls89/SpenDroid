@file:OptIn(ExperimentalMaterial3Api::class)

package com.spendroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.graphicsLayer
import com.spendroid.ui.theme.Motion
import com.spendroid.domain.Category
import com.spendroid.widget.MAIN_EXTRA_CATEGORY
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
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
import com.spendroid.ui.SpendingScreen
import com.spendroid.ui.theme.BudgetTheme

enum class AppScreen(
    val route: String,
    val title: String,
    /** Bottom-bar label: "Recurring rules" does not fit under an icon. */
    val shortTitle: String,
    val icon: ImageVector,
) {
    Home("home", "Dashboard", "Home", Icons.Filled.Home),
    Spending("spending", "Spending", "Spending", Icons.AutoMirrored.Filled.ReceiptLong),
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

    /** A category tapped on a widget, waiting to be opened. */
    private val widgetCategory = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) widgetCategory.value = intent?.getStringExtra(MAIN_EXTRA_CATEGORY)
        setContent {
            BudgetTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var screenRoute by rememberSaveable { mutableStateOf(AppScreen.Home.route) }
                val screen = AppScreen.from(screenRoute)
                var showLinkDialog by remember { mutableStateOf(false) }
                var showManualDialog by remember { mutableStateOf(false) }

                val navigate: (AppScreen) -> Unit = { target -> screenRoute = target.route }
                // Keeps each tab's scroll position and fields while another is showing, so
                // coming back is coming back rather than starting again.
                val tabStates = rememberSaveableStateHolder()

                // A widget row names a category: open Spending filtered to it.
                val pendingCategory = widgetCategory.value
                LaunchedEffect(pendingCategory) {
                    val category = Category.entries.firstOrNull { it.name == pendingCategory } ?: return@LaunchedEffect
                    viewModel.setCategoryFilter(category)
                    navigate(AppScreen.Spending)
                    widgetCategory.value = null
                }

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
                                            // A small bounce on arrival says which tab took the
                                            // tap; Material's own pill slides across beneath it.
                                            val bounce = remember { Animatable(1f) }
                                            val selected = destination == screen
                                            LaunchedEffect(selected) {
                                                if (selected) {
                                                    bounce.snapTo(0.8f)
                                                    bounce.animateTo(
                                                        1f,
                                                        spring(
                                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessMedium,
                                                        ),
                                                    )
                                                }
                                            }
                                            // Label is hidden unless selected, so the icon
                                            // has to carry the name for a screen reader.
                                            Icon(
                                                destination.icon,
                                                contentDescription = destination.title,
                                                modifier = Modifier.graphicsLayer {
                                                    scaleX = bounce.value
                                                    scaleY = bounce.value
                                                },
                                            )
                                        },
                                        label = { Text(destination.shortTitle) },
                                        alwaysShowLabel = false,
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            // Material's fade-through: the old screen gets out of the way fast and
                            // the new one settles in, so the change reads as "somewhere else"
                            // without the sideways slide that implies an order between tabs.
                            AnimatedContent(
                                targetState = screen,
                                transitionSpec = {
                                    (
                                        fadeIn(Motion.arrive(Motion.MEDIUM - 90, delay = 90)) +
                                            scaleIn(Motion.arrive(Motion.MEDIUM - 90, delay = 90), initialScale = 0.94f)
                                        ).togetherWith(fadeOut(tween(90, easing = Motion.EmphasizedAccelerate)))
                                },
                                label = "tab",
                            ) { shown ->
                                tabStates.SaveableStateProvider(shown.route) {
                                    when (shown) {
                                        AppScreen.Home -> HomeScreen(
                                            state = state,
                                            onRefresh = viewModel::refresh,
                                            onRelink = viewModel::relink,
                                            onSeeAllTransactions = { navigate(AppScreen.Spending) },
                                            onSetBudgetGoal = viewModel::setBudgetGoal,
                                            onLinkBank = { showLinkDialog = true },
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
                                        )

                                        AppScreen.Settings -> SettingsScreen(
                                            state = state,
                                            onSave = viewModel::saveSecret,
                                            onSetBudgetModel = viewModel::setBudgetModel,
                                            onSetCardTiming = viewModel::setCardTiming,
                                            onExport = viewModel::exportTo,
                                            onImport = viewModel::importFrom,
                                            onClearData = viewModel::clearData,
                                            onCheckUpdate = viewModel::checkForUpdate,
                                            onOpenInstallSettings = viewModel::openInstallPermissionSettings,
                                            secretId = viewModel.secretIdValue.collectAsStateWithLifecycle().value,
                                            secretKey = viewModel.secretKeyValue.collectAsStateWithLifecycle().value,
                                            notificationTime = viewModel.notificationTime.collectAsStateWithLifecycle().value,
                                            onSaveNotificationTime = viewModel::saveNotificationTime,
                                        )
                                    }
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

                state.justLinked?.let { linkedName ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissLinkPrompt,
                        title = { Text("$linkedName linked") },
                        text = {
                            Text(
                                "Its accounts are syncing now. Would you like to link another " +
                                    "bank while you are here?",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.dismissLinkPrompt()
                                showLinkDialog = true
                            }) { Text("Link another") }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissLinkPrompt) { Text("Done") }
                        },
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

    /** singleTask: a widget tap while the app is open arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(MAIN_EXTRA_CATEGORY)?.let { widgetCategory.value = it }
    }
}
