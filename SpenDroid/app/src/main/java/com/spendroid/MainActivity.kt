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
    /** Bottom-bar label, shorter where the title would not fit under an icon. */
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

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val viewModel: RootViewModel by viewModels { RootViewModel.factory(application) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Opened from the roundup notification: show the roundup until it is dismissed. */
    private val showRoundup = mutableStateOf(false)

    /** An import open on the phone: from Accounts, or a file shared or opened from another app. */
    private val importRequest = mutableStateOf<com.spendroid.ui.ImportRequest?>(null)

    /** A bank's file handed over by another app - Share, or Open with - to import. */
    private fun importFrom(intent: Intent?): com.spendroid.ui.ImportRequest? {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            else -> null
        } ?: return null
        return com.spendroid.ui.ImportRequest(uri = uri)
    }

    /** From a notification: a bill's sheet to open on Regular, or an account's forecast. */
    private val openRule = mutableStateOf<String?>(null)
    private val openForecast = mutableStateOf<String?>(null)
    /** A manual account whose balance check is open. */
    private val checkBalance = mutableStateOf<String?>(null)
    /** From Penny's question notification: her questions, asked now whatever "later" said. */
    private val askPenny = mutableStateOf(false)

    private fun takeOpeners(intent: Intent?) {
        intent?.getStringExtra(com.spendroid.work.MoneyWatchAlerts.EXTRA_OPEN_RULE)?.let { openRule.value = it }
        intent?.getStringExtra(com.spendroid.work.MoneyWatchAlerts.EXTRA_OPEN_FORECAST)?.let { openForecast.value = it }
        intent?.getStringExtra(com.spendroid.work.ContractAlerts.EXTRA_OPEN_CONTRACT)?.let { openRule.value = it }
        intent?.getStringExtra(com.spendroid.work.ManualAlerts.EXTRA_OPEN_BALANCE_CHECK)?.let { checkBalance.value = it }
        if (intent?.getBooleanExtra(com.spendroid.work.SpendQuestions.EXTRA_ASK, false) == true) askPenny.value = true
        intent?.getStringExtra(com.spendroid.work.ManualAlerts.EXTRA_OPEN_IMPORT)?.let { importRequest.value = com.spendroid.ui.ImportRequest(accountId = it) }
    }

    /** Locked behind Android's own fingerprint, face or PIN prompt until unlocked. */
    private val locked = mutableStateOf(false)
    private var prompting = false

    /** Asks Android to confirm it's the phone's owner; [done] hears whether it was. */
    private fun authenticate(title: String, done: (Boolean) -> Unit) {
        if (prompting) return
        prompting = true
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    done(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    done(false)
                }
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    private fun unlock() = authenticate("Unlock SpenDroid") { ok ->
        if (ok) {
            locked.value = false
            com.spendroid.data.Privacy.unlocked = true
        }
    }

    /** A blank card in recent apps, and no screenshots, when the user asks for it. */
    private fun applyHideInRecents(hide: Boolean) {
        if (hide) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** A category tapped on a widget, waiting to be opened. */
    private val widgetCategory = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val privacy = com.spendroid.data.Privacy(this)
        applyHideInRecents(privacy.hideInRecents)
        // A fresh start - the phone restarted, the app was closed - is locked from the off.
        if (privacy.lockEnabled && !com.spendroid.data.Privacy.unlocked) locked.value = true
        if (savedInstanceState == null) {
            widgetCategory.value = intent?.getStringExtra(MAIN_EXTRA_CATEGORY)
            showRoundup.value = intent?.getBooleanExtra(com.spendroid.work.EXTRA_OPEN_ROUNDUP, false) == true
            importRequest.value = importFrom(intent)
            takeOpeners(intent)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val look by viewModel.look.collectAsStateWithLifecycle()
            BudgetTheme(accent = look.accent) {
            val glow = remember { GlowState() }
            // Locked, the app isn't drawn at all - so no sheet or dialog left open can show over the lock.
            if (locked.value) com.spendroid.ui.LockScreen(onUnlock = ::unlock)
            else CompositionLocalProvider(LocalAccountColours provides look.accountColours, LocalGlow provides glow) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                // Every header's droid wears the budget's face.
                val face = com.spendroid.ui.droidFaceOf(state.budget, state.syncing)
                androidx.compose.runtime.SideEffect { com.spendroid.ui.DroidNow.face = face }
                var screenRoute by rememberSaveable { mutableStateOf(AppScreen.Home.route) }
                val screen = AppScreen.from(screenRoute)
                var showLinkDialog by remember { mutableStateOf(false) }
                // Penny's questions: asked in a sheet as the app opens, unless put off for now.
                val billsContext = androidx.compose.ui.platform.LocalContext.current
                val billsAnswered = com.spendroid.ui.PennyBills.answered.intValue
                val questions = remember(state.seenSpends, state.duplicateQuestions, state.manualCoverage, state.rules, state.ignoredRules, state.budget == null, billsAnswered) {
                    com.spendroid.ui.pennyQuestions(state, bills = com.spendroid.ui.PennyBills.toAsk(billsContext, state))
                }
                var asking by rememberSaveable { mutableStateOf(false) }
                // A payee said to be just spending stays so at a new amount, which is a new rule.
                LaunchedEffect(state.rules, state.ignoredRules, billsAnswered) {
                    com.spendroid.ui.PennyBills.stillToIgnore(billsContext, state).forEach { viewModel.setRuleIgnored(it, true) }
                }
                // Something Penny has noticed: said on Home once, then known.
                val newsContext = androidx.compose.ui.platform.LocalContext.current
                val news = remember(state.rules, state.accounts, state.budget == null) { com.spendroid.ui.PennyNews.next(newsContext, state) }
                LaunchedEffect(news?.key, screen) {
                    if (news != null && screen == AppScreen.Home) com.spendroid.ui.PennyNews.know(newsContext, news.key)
                }
                // Today's allowance, the payday moment and the last cycle's review: shown over the app.
                var showToday by rememberSaveable { mutableStateOf(false) }
                var showReview by rememberSaveable { mutableStateOf(false) }
                var showPayday by remember { mutableStateOf(false) }
                var review by remember { mutableStateOf<com.spendroid.domain.CycleReview?>(null) }
                var reviewLoading by remember { mutableStateOf(false) }
                val moments = remember { getSharedPreferences(com.spendroid.data.Stores.MOMENTS, MODE_PRIVATE) }
                // Payday, once a cycle: on the day pay came, or the day after if the app wasn't opened.
                LaunchedEffect(state.budget?.cycleStart, state.budget?.asOf) {
                    val b = state.budget ?: return@LaunchedEffect
                    val fresh = !b.asOf.isBefore(b.cycleStart) && !b.asOf.isAfter(b.cycleStart.plusDays(1))
                    if (fresh && moments.getString("payday_seen", null) != b.cycleStart.toString() && (state.hasCredentials || state.setupDone)) {
                        showPayday = true
                    }
                }
                LaunchedEffect(showPayday, showReview) {
                    if ((showPayday || showReview) && review == null && !reviewLoading) {
                        reviewLoading = true
                        review = viewModel.lastCycleReview()
                        reviewLoading = false
                    }
                }
                var showManualDialog by remember { mutableStateOf(false) }
                // The forecast screen, for this account; "" for the budget's own.
                var forecastFor by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(openForecast.value) {
                    openForecast.value?.let { forecastFor = it; openForecast.value = null }
                }
                LaunchedEffect(openRule.value) {
                    if (openRule.value != null) screenRoute = AppScreen.Rules.route
                }

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

                // With a bank link, or once setup without one is finished, the app itself.
                if (state.hasCredentials || state.setupDone) {
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
                                        DrawerTarget.TODAY -> showToday = true
                                        DrawerTarget.REVIEW -> showReview = true
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
                                            onHideInRecents = ::applyHideInRecents,
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
                            }
                        }
                    }
                    }
                    }
                } else {
                    com.spendroid.ui.SetupFlow(
                        state = state,
                        onAddAccount = viewModel::addManualAccount,
                        onRemoveAccount = viewModel::removeManualAccount,
                        onStartFromToday = { name, bank, balance, pay ->
                            viewModel.startFromToday(name, bank, balance, pay.payMinor, pay.cadence, pay.anchorDay, pay.lastPaid)
                        },
                        onAddPay = { pay, accountId ->
                            viewModel.addPay(pay.payMinor, pay.cadence, pay.anchorDay, pay.lastPaid, accountId)
                        },
                        onSetBalance = viewModel::setManualBalance,
                        onIgnoreRule = viewModel::setRuleIgnored,
                        onSaveSources = viewModel::saveSpendSources,
                        onUpdateAccount = viewModel::updateAccount,
                        onReload = viewModel::reloadLocal,
                        onFinish = viewModel::finishSetup,
                        onRestore = { uri, passphrase -> viewModel.importFrom(uri, passphrase) },
                        onCancelRestore = viewModel::cancelRestore,
                        gocardless = {
                            OnboardingScreen(
                                state = state,
                                onSaveSecret = viewModel::saveSecret,
                                onSearch = viewModel::loadInstitutions,
                                onLink = viewModel::link,
                            )
                        },
                        importScreen = { accountId, done ->
                            com.spendroid.ui.ImportScreen(
                                request = com.spendroid.ui.ImportRequest(accountId = accountId),
                                accounts = state.accounts,
                                onClose = done,
                                onAdded = viewModel::reloadLocal,
                            )
                        },
                    )
                }

                if (state.hasCredentials || state.setupDone) {
                    fun seenPayday() {
                        state.budget?.let { moments.edit().putString("payday_seen", it.cycleStart.toString()).apply() }
                        showPayday = false
                    }
                    when {
                        showReview -> com.spendroid.ui.ReviewScreen(review, reviewLoading, onBack = { showReview = false })
                        showPayday -> com.spendroid.ui.PaydayScreen(state, review, onReview = { seenPayday(); showReview = true }, onDone = { seenPayday() })
                        showToday -> com.spendroid.ui.TodayScreen(state, onBack = { showToday = false })
                    }
                }

                if (showRoundup.value && (state.hasCredentials || state.setupDone)) {
                    com.spendroid.ui.RoundupScreen(
                        state = state,
                        onBack = { showRoundup.value = false },
                        onSeeQuestions = {
                            showRoundup.value = false
                            navigate(AppScreen.Spending)
                        },
                    )
                }

                forecastFor?.takeIf { state.hasCredentials || state.setupDone }?.let { id ->
                    com.spendroid.ui.ForecastScreen(state, id.ifEmpty { null }, onBack = { forecastFor = null })
                }

                importRequest.value?.takeIf { state.hasCredentials || state.setupDone }?.let { request ->
                    // Keyed on the request, so a second file shared while one is open starts afresh.
                    androidx.compose.runtime.key(request) {
                        com.spendroid.ui.ImportScreen(
                            request = request,
                            accounts = state.accounts,
                            onClose = { importRequest.value = null },
                            onAdded = viewModel::reloadLocal,
                        )
                    }
                }

                // Penny asks as the app opens - each time it comes back to the front - with nothing
                // else over it, unless "Ask me later" was said in the last six hours. Her
                // notification asks at once.
                if (state.hasCredentials || state.setupDone) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    var opened by remember { mutableIntStateOf(0) }
                    var offered by remember { mutableIntStateOf(-1) }
                    androidx.compose.runtime.DisposableEffect(lifecycle) {
                        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) opened++
                        }
                        lifecycle.addObserver(watch)
                        onDispose { lifecycle.removeObserver(watch) }
                    }
                    val clear = !showToday && !showReview && !showPayday && !showRoundup.value && forecastFor == null && importRequest.value == null
                    LaunchedEffect(opened, questions.isNotEmpty(), clear, askPenny.value, state.budget == null) {
                        // Her notification tapped once the question was already answered: nothing to
                        // ask, and nothing left waiting to open the sheet at the next question.
                        if (askPenny.value && questions.isEmpty() && state.budget != null) askPenny.value = false
                        if (questions.isEmpty() || !clear) return@LaunchedEffect
                        if (askPenny.value) {
                            asking = true
                            askPenny.value = false
                        } else if (offered != opened && com.spendroid.ui.PennyAsk.due(context)) {
                            asking = true
                            offered = opened
                        }
                    }
                    // Open until it closes itself: answering the last question empties the list at once,
                    // and the "noted" page - and its Change - must still be seen.
                    if (asking) {
                        com.spendroid.ui.PennyQuestionSheet(
                            questions = questions,
                            accounts = state.accounts,
                            sources = viewModel.spendSources.collectAsStateWithLifecycle().value,
                            answers = com.spendroid.ui.PennyAnswers(
                                onRepeat = viewModel::answerRepeat,
                                onAssign = { id, account -> viewModel.assignSeenSpend(id, account, always = false) },
                                onKeepSeen = viewModel::keepSeenSpend,
                                onDismissSeen = viewModel::dismissSeenSpend,
                                onSame = viewModel::confirmDuplicate,
                                onKeepVanished = viewModel::keepVanished,
                                onRemoveVanished = viewModel::removeVanished,
                                onCardCheck = viewModel::answerCardCheck,
                                onBill = { rule, isBill ->
                                    com.spendroid.ui.PennyBills.answer(context, rule, isBill)
                                    viewModel.setRuleIgnored(rule.key, !isBill)
                                },
                            ),
                            onLater = {
                                com.spendroid.ui.PennyAsk.later(context)
                                asking = false
                            },
                            onDone = { asking = false },
                        )
                    }
                }

                // A manual account's balance check, from Home, Accounts or a reminder.
                checkBalance.value?.let { id -> state.accounts.firstOrNull { it.id == id } }?.let { account ->
                    com.spendroid.ui.BalanceCheckSheet(
                        account = account,
                        accounts = state.accounts,
                        onPreview = { typed -> viewModel.previewBalanceCheck(account.id, typed) },
                        onApply = { typed, move ->
                            viewModel.applyBalanceCheck(account.id, typed, move)
                            checkBalance.value = null
                        },
                        onDismiss = { checkBalance.value = null },
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
                        onAdd = { payee, direction, amountMinor, currency, cadence, anchorDay, startDate, accountId ->
                            showManualDialog = false
                            viewModel.addManualRule(payee, direction, amountMinor, currency, cadence, anchorDay, startDate, accountId)
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
        // Away longer than the user allows, and it locks again.
        val privacy = com.spendroid.data.Privacy(this)
        val away = com.spendroid.data.Privacy.leftAt
        if (privacy.lockEnabled && away > 0L && System.currentTimeMillis() - away >= privacy.lockAfter.millis) {
            com.spendroid.data.Privacy.unlocked = false
            locked.value = true
        }
        com.spendroid.data.Privacy.leftAt = 0L
        // An import allowed from its notification while the app was in the background shows at once.
        viewModel.reloadLocal()
        lifecycleScope.launch { com.spendroid.widget.refreshWidgetsIfStale(applicationContext) }
    }

    override fun onStop() {
        super.onStop()
        // Asking to unlock takes the app off screen on some phones; that isn't leaving it.
        // Nor is turning the phone, which stops and restarts the screen.
        if (!prompting && !locked.value && !isChangingConfigurations) com.spendroid.data.Privacy.leftAt = System.currentTimeMillis()
    }

    /** singleTask: a widget tap while the app is open arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(MAIN_EXTRA_CATEGORY)?.let { widgetCategory.value = it }
        if (intent.getBooleanExtra(com.spendroid.work.EXTRA_OPEN_ROUNDUP, false)) showRoundup.value = true
        importFrom(intent)?.let { importRequest.value = it }
        takeOpeners(intent)
    }
}
