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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.spendroid.ui.theme.Motion
import com.spendroid.domain.Category
import com.spendroid.widget.MAIN_EXTRA_CATEGORY
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spendroid.ui.OnboardingScreen
import com.spendroid.ui.RootViewModel
import com.spendroid.ui.theme.BudgetTheme

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
        // "Wasn't installed - tap to install it": the kept download, to Android's prompt again.
        if (intent?.getBooleanExtra(com.spendroid.data.UpdateNotice.EXTRA_INSTALL, false) == true) {
            intent.removeExtra(com.spendroid.data.UpdateNotice.EXTRA_INSTALL)
            viewModel.installDownloadedUpdate()
        }
        intent?.getStringExtra(com.spendroid.work.MoneyWatchAlerts.EXTRA_OPEN_RULE)?.let { openRule.value = it }
        intent?.getStringExtra(com.spendroid.work.MoneyWatchAlerts.EXTRA_OPEN_FORECAST)?.let { openForecast.value = it }
        intent?.getStringExtra(com.spendroid.work.ContractAlerts.EXTRA_OPEN_CONTRACT)?.let { openRule.value = it }
        intent?.getStringExtra(com.spendroid.work.RenewalAlerts.EXTRA_OPEN_RENEWAL)?.let { openRule.value = com.spendroid.ui.RENEWAL_LINK + it }
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
                val linkDialog = remember { mutableStateOf(false) }
                var showLinkDialog by linkDialog
                // Penny's questions: asked in a sheet as the app opens, unless put off for now.
                val billsContext = androidx.compose.ui.platform.LocalContext.current
                val billsAnswered = com.spendroid.ui.PennyBills.answered.intValue
                val questions = remember(state.seenSpends, state.duplicateQuestions, state.manualCoverage, state.rules, state.ignoredRules, state.budget == null, billsAnswered) {
                    com.spendroid.ui.pennyQuestions(state, bills = com.spendroid.ui.PennyBills.toAsk(billsContext, state))
                }
                val askingState = rememberSaveable { mutableStateOf(false) }
                var asking by askingState
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
                val todayState = rememberSaveable { mutableStateOf(false) }
                var showToday by todayState
                var showReview by rememberSaveable { mutableStateOf(false) }
                var showPayday by remember { mutableStateOf(false) }
                var review by remember { mutableStateOf<com.spendroid.domain.CycleReview?>(null) }
                var reviewLoading by remember { mutableStateOf(false) }
                val moments = remember { com.spendroid.data.Stores.prefs(this@MainActivity, com.spendroid.data.Stores.MOMENTS) }
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
                val manualDialog = remember { mutableStateOf(false) }
                var showManualDialog by manualDialog
                // The forecast screen, for this account; "" for the budget's own.
                val forecastState = rememberSaveable { mutableStateOf<String?>(null) }
                var forecastFor by forecastState
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
                    val spendingTabState = rememberSaveable { mutableIntStateOf(0) }
                    var spendingTab by spendingTabState
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
                                // Talking on the screen below, Penny isn't in the banner too.
                                val talks = remember(state, screen) { com.spendroid.ui.pennyTalks(state, screen) }
                                Wordmark(
                                    text = if (screen == AppScreen.Home) "SpenDroid" else screen.title,
                                    colour = glowColour,
                                    droid = !talks,
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
                                    MainTab(
                                        shown, state, viewModel, look, navigate, news, questions,
                                        linkDialog, forecastState, todayState, askingState, spendingTabState, manualDialog,
                                        checkBalance, importRequest, openRule, ::authenticate, ::applyHideInRecents,
                                    )
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
                    val clear = !showToday && !showReview && !showPayday && !showRoundup.value && forecastFor == null && importRequest.value == null
                    PennyAsking(state, viewModel, questions, askingState, clear, askPenny)
                }

                AppDialogs(state, viewModel, checkBalance, linkDialog, manualDialog)
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
