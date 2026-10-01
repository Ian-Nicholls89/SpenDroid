@file:OptIn(ExperimentalMaterial3Api::class)

package com.spendroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.spendroid.ui.HeaderGlow
import com.spendroid.ui.LocalAccountColours
import com.spendroid.ui.AppDrawer
import com.spendroid.ui.DrawerTarget
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.spendroid.ui.GlowState
import com.spendroid.ui.LocalGlow
import com.spendroid.ui.Wordmark
import com.spendroid.ui.theme.Charcoal
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import com.spendroid.ui.CardAlertsSection
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
    Rules("rules", "Regular", "Regular", Icons.Filled.Repeat),
    Settings("settings", "Settings", "Settings", Icons.Filled.Settings);

    companion object {
        fun from(route: String): AppScreen = entries.firstOrNull { it.route == route } ?: Home
    }
}

/**
 * The tabs at the foot, nzb360's way: the chosen one's icon in a pill of the accent, every label
 * shown. Material's bar hid the labels and tinted the pill to its own scheme.
 */
@Composable
internal fun PillNavigationBar(screens: List<AppScreen>, selected: AppScreen, onSelect: (AppScreen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F2025))
            .navigationBarsPadding()
            .height(72.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        screens.forEach { destination ->
            val on = destination == selected
            val pill by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                Motion.change(Motion.SHORT),
                label = "pill",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(destination) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(pill)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        tint = if (on) Color(0xFF111111) else Color(0xFFAAB0BB),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    destination.shortTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) Color.White else Color(0xFFAAB0BB),
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: RootViewModel by viewModels { RootViewModel.factory(application) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Opened from the roundup notification: show the roundup until it is dismissed. */
    private val showRoundup = mutableStateOf(false)

    /** A category tapped on a widget, waiting to be opened. */
    private val widgetCategory = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            widgetCategory.value = intent?.getStringExtra(MAIN_EXTRA_CATEGORY)
            showRoundup.value = intent?.getBooleanExtra(com.spendroid.work.EXTRA_OPEN_ROUNDUP, false) == true
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val look by viewModel.look.collectAsStateWithLifecycle()
            BudgetTheme(accent = look.accent) {
            val glow = remember { GlowState() }
            CompositionLocalProvider(LocalAccountColours provides look.accountColours, LocalGlow provides glow) {
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
                    val drawer = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    var spendingTab by rememberSaveable { mutableIntStateOf(0) }
                    ModalNavigationDrawer(
                        drawerState = drawer,
                        drawerContent = {
                            AppDrawer(
                                state = state,
                                current = when (screen) {
                                    AppScreen.Home -> DrawerTarget.OVERVIEW
                                    AppScreen.Rules -> DrawerTarget.REGULAR
                                    AppScreen.Settings -> DrawerTarget.SETTINGS
                                    AppScreen.Spending -> DrawerTarget.INSIGHTS.takeIf { spendingTab == 4 }
                                    else -> null
                                },
                                onTarget = { target ->
                                    when (target) {
                                        DrawerTarget.OVERVIEW -> navigate(AppScreen.Home)
                                        DrawerTarget.REGULAR -> navigate(AppScreen.Rules)
                                        DrawerTarget.INSIGHTS -> {
                                            spendingTab = 4
                                            navigate(AppScreen.Spending)
                                        }
                                        DrawerTarget.SETTINGS -> navigate(AppScreen.Settings)
                                    }
                                    scope.launch { drawer.close() }
                                },
                                onAccount = { account ->
                                    viewModel.setAccountFilter(account.id)
                                    spendingTab = 0
                                    navigate(AppScreen.Spending)
                                    scope.launch { drawer.close() }
                                },
                            )
                        },
                    ) {
                    Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
                    val glowColour by animateColorAsState(
                        glow.colour ?: MaterialTheme.colorScheme.primary,
                        Motion.change(Motion.MEDIUM),
                        label = "glow",
                    )
                    HeaderGlow(glowColour, height = 260.dp)
                    Scaffold(
                        // The glow behind the header shows through: nzb360's warm wash at the top.
                        containerColor = Color.Transparent,
                        // Transparent has no colour of its own to read text against, so say it.
                        contentColor = Charcoal.Text,
                        topBar = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(start = 6.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.padding(end = 6.dp)) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color(0xFFCFD2D8))
                                }
                                Wordmark(
                                    text = if (screen == AppScreen.Home) "SpenDroid" else screen.title,
                                    colour = glowColour,
                                )
                            }
                        },
                        bottomBar = {
                            PillNavigationBar(
                                screens = AppScreen.entries,
                                selected = screen,
                                onSelect = navigate,
                            )
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
                                            onSeeAccount = { id ->
                                                viewModel.setAccountFilter(id)
                                                navigate(AppScreen.Spending)
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
                                            tabIndex = spendingTab,
                                            onTabIndex = { spendingTab = it },
                                            prompts = {
                                                com.spendroid.ui.DuplicatePrompts(
                                                    questions = state.duplicateQuestions,
                                                    accounts = state.accounts,
                                                    onSame = viewModel::confirmDuplicate,
                                                    onKeep = viewModel::keepVanished,
                                                    onRemove = viewModel::removeVanished,
                                                )
                                                com.spendroid.ui.SeenSpendPrompts(
                                                    seen = state.seenSpends,
                                                    accounts = state.accounts,
                                                    onAssign = viewModel::assignSeenSpend,
                                                    onKeep = viewModel::keepSeenSpend,
                                                    onDismiss = viewModel::dismissSeenSpend,
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
                                                )
                                            },
                                        )
                                    }
                                }
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

                if (showRoundup.value && state.hasCredentials) {
                    com.spendroid.ui.RoundupScreen(
                        state = state,
                        onBack = { showRoundup.value = false },
                        onSeeQuestions = {
                            showRoundup.value = false
                            navigate(AppScreen.Spending)
                        },
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
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Each time the app comes to the front, the widgets are redrawn and the watch sent what is on
     * the phone - free, since nothing is fetched - so both are current whenever the app has been
     * looked at.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            com.spendroid.widget.refreshWidgets(applicationContext)
            com.spendroid.widget.refreshCardWidgets(applicationContext)
        }
    }

    /** singleTask: a widget tap while the app is open arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(MAIN_EXTRA_CATEGORY)?.let { widgetCategory.value = it }
        if (intent.getBooleanExtra(com.spendroid.work.EXTRA_OPEN_ROUNDUP, false)) showRoundup.value = true
    }
}
