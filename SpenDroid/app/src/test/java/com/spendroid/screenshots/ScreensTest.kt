package com.spendroid.screenshots

import androidx.compose.ui.test.performClick
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.spendroid.AppScreen
import com.spendroid.PillNavigationBar
import com.spendroid.ui.HeaderGlow
import com.spendroid.ui.HomeScreen
import com.spendroid.ui.Wordmark
import com.spendroid.ui.theme.BudgetTheme
import com.spendroid.ui.theme.Charcoal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screens as they render, from made-up data. Run with -Pscreenshots; images go to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScreensTest {

    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Frame(screen: AppScreen, content: @Composable () -> Unit) {
        BudgetTheme {
            val glow = androidx.compose.runtime.remember { com.spendroid.ui.GlowState() }
            androidx.compose.runtime.CompositionLocalProvider(com.spendroid.ui.LocalGlow provides glow) {
            Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
                HeaderGlow(glow.colour ?: MaterialTheme.colorScheme.primary, height = 260.dp)
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = Charcoal.Text,
                    topBar = {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                            Wordmark(if (screen == AppScreen.Home) "SpenDroid" else screen.title, glow.colour ?: MaterialTheme.colorScheme.primary)
                        }
                    },
                    bottomBar = { PillNavigationBar(AppScreen.entries, screen) {} },
                ) { padding -> Box(Modifier.padding(padding)) { content() } }
            }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun homeCard() {
        val state = Sample.state()
        shoot("home-card", AppScreen.Home) {
            HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {}, initialPage = 1)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun home() {
        val state = Sample.state()
        compose.setContent {
            Frame(AppScreen.Home) {
                HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {})
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/home.png")
    }

    private fun shoot(name: String, screen: AppScreen, content: @Composable () -> Unit) {
        compose.setContent { Frame(screen) { content() } }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun spending() {
        val state = Sample.state()
        shoot("spending", AppScreen.Spending) {
            com.spendroid.ui.SpendingScreen(
                state = state, onRefresh = {}, onToggleRecurring = {}, onToggleInternal = {}, onQueryChange = {},
                onAccountFilter = {}, onOverrideCategory = { _, _ -> }, onAlwaysCategorise = { _, _ -> },
                onMarkTransfer = { _, _ -> }, onMarkCardPayment = { _, _ -> }, onCategoryFilter = {}, onSetBudgetGoal = { _, _ -> },
                prompts = {
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        com.spendroid.ui.DuplicatePrompts(Sample.questions(), state.accounts, {}, {}, {})
                        com.spendroid.ui.SeenSpendPrompts(Sample.seen(), state.accounts, { _, _ -> }, {}, {})
                    }
                },
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun accounts() {
        val state = Sample.state()
        shoot("accounts", AppScreen.Accounts) {
            com.spendroid.ui.AccountManagementScreen(state = state, onLink = {}, onRelink = {}, onUpdateAccount = {})
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun rules() {
        val state = Sample.state()
        shoot("rules", AppScreen.Rules) {
            com.spendroid.ui.RecurringRulesScreen(rules = state.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {}, budget = state.budget)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun settings() {
        val state = Sample.state()
        shoot("settings", AppScreen.Settings) {
            com.spendroid.ui.SettingsScreen(
                state = state, onSave = { _, _ -> }, onSetBudgetModel = {}, onSetCardTiming = {}, onExport = {}, onImport = {},
                onClearData = {}, onCheckUpdate = {}, onOpenInstallSettings = {}, secretId = "abc", secretKey = "def",
                notificationTime = "21:00", onSaveNotificationTime = {},
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun roundup() {
        val state = Sample.state()
        compose.setContent { BudgetTheme { com.spendroid.ui.RoundupScreen(state, onBack = {}, onSeeQuestions = {}) } }
        compose.onRoot().captureRoboImage("build/screenshots/roundup.png")
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun transaction() {
        val state = Sample.state()
        val tx = state.transactions.first { it.payee == "PORTON STORES" }
        compose.setContent {
            BudgetTheme {
                com.spendroid.ui.TransactionDetailSheet(
                    transaction = tx, userRules = emptyList(), onDismiss = {}, onOverrideCategory = { _, _ -> },
                    onAlwaysCategorise = { _, _ -> }, onMarkTransfer = { _, _ -> }, similarCount = 2,
                    suggestions = listOf(com.spendroid.domain.Category.WORK_LUNCH, com.spendroid.domain.Category.GROCERIES),
                    onTreatAsBill = { _, _ -> }, accountLabel = "Personal Account",
                    payeeCycle = com.spendroid.ui.PayeeCycle(1_936, 2, 2_400),
                    billNote = "Monthly regular bill · counted in the Nectar Card bill",
                )
            }
        }
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/transaction.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun insights() {
        val state = Sample.state()
        shoot("insights", AppScreen.Spending) { com.spendroid.ui.InsightsScreen(state, onSetBudgetGoal = { _, _ -> }) }
    }

    @Test
    fun drawer() {
        val state = Sample.state()
        compose.setContent { BudgetTheme { com.spendroid.ui.AppDrawer(state, com.spendroid.ui.DrawerTarget.OVERVIEW, {}, {}) } }
        compose.onRoot().captureRoboImage("build/screenshots/drawer.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun moveImport() {
        val state = Sample.state()
        compose.setContent {
            Frame(AppScreen.Accounts) {
                com.spendroid.ui.MoveImportDialog(
                    batch = com.spendroid.web.WebImport.Batch("b1", "acc-joint", "Statement-export.csv", 326, 0L, "2025-01-02", "2025-12-30"),
                    accounts = state.accounts,
                    rows = List(312) { "SAINSBURYS S/MKTS" to 4_210L } + List(14) { "PAYMENT RECEIVED - THANK YOU" to -20_000L },
                    onMove = { _, _ -> },
                    onDismiss = {},
                    initial = state.accounts.first { it.accountType == com.spendroid.data.db.AccountType.CREDIT_CARD }.id,
                )
            }
        }
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/move-import.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun phoneImport() {
        val state = Sample.state()
        val text = javaClass.classLoader!!.getResource("statements/unknown.csv")!!.readText()
        val parsed = com.spendroid.importer.StatementFile.parse(text)
        val guess = com.spendroid.importer.StatementFile.guess(parsed, null, false)
        // As if Ref had been guessed for who, to show the amber "?".
        val layout = guess.copy(roles = guess.roles.mapIndexed { i, r -> if (i == 1) "payee" else if (r == "payee") "" else r }, sure = guess.sure.mapIndexed { i, s -> if (i == 1) false else s })
        val loaded = com.spendroid.ui.Loaded("statement-2025.csv", parsed)
        val rows = com.spendroid.importer.StatementFile.read(parsed, guess)
        val check = com.spendroid.web.WebImport.Check(
            rows.filter { it.ok }.map { com.spendroid.web.WebImport.Row(it.date!!, it.amount!!, it.payee) }, 1, 0, null,
        )
        val which = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            Frame(AppScreen.Accounts) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    when (which.intValue) {
                        0 -> com.spendroid.ui.FileStep(loaded, null, state.accounts, state.accounts[1].id, {}, {})
                        1 -> com.spendroid.ui.MatchStep(parsed, layout, com.spendroid.importer.StatementFile.read(parsed, layout), {})
                        else -> com.spendroid.ui.CheckStep(loaded, guess, rows, check, state.accounts[1], {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/import-file.png")
        which.intValue = 1
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/import-match.png")
        which.intValue = 2
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/import-check.png")
    }

    /** The import as the app shows it - over everything, outside the frame - from a shared file. */
    @Test
    @Config(qualifiers = "w411dp-h900dp-xxhdpi")
    fun phoneImportAsShown() {
        val state = Sample.state()
        val file = java.io.File.createTempFile("statement", ".csv")
        file.writeText(javaClass.classLoader!!.getResource("statements/unknown.csv")!!.readText())
        compose.setContent {
            BudgetTheme {
                com.spendroid.ui.ImportScreen(
                    com.spendroid.ui.ImportRequest(accountId = state.accounts[1].id, uri = android.net.Uri.fromFile(file)),
                    state.accounts, onClose = {}, onAdded = {},
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("statement", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("build/screenshots/import-shown.png")
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun transactionRenamed() {
        val state = Sample.state()
        val tx = state.transactions.first { it.payee == "PORTON STORES" }
        com.spendroid.data.PayeeNames.load(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        com.spendroid.data.PayeeNames.set("PORTON STORES", "Corner shop")
        try {
            compose.setContent {
                BudgetTheme {
                    com.spendroid.ui.TransactionDetailSheet(
                        transaction = tx, userRules = emptyList(), onDismiss = {}, onOverrideCategory = { _, _ -> },
                        onAlwaysCategorise = { _, _ -> }, onMarkTransfer = { _, _ -> }, accountLabel = "Personal Account",
                    )
                }
            }
            compose.waitForIdle()
            com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/transaction-renamed.png")
        } finally {
            com.spendroid.data.PayeeNames.set("PORTON STORES", null)
        }
    }

    /** 4.5: the forecast on Home, an overdraft warning, the forecast screen, price rises on Regular, the new settings and the lock. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun outlookScreens() {
        val base = Sample.state()
        val today = java.time.LocalDate.now()
        val outlook = com.spendroid.domain.Outlook.of(base.budget, base.accounts, base.rules, emptySet(), base.transactions, today, false)
        val joint = base.accounts.first { it.id == "acc-joint" }
        val warning = com.spendroid.domain.Forecast.Warning(
            joint, today.plusDays(12), 8_600,
            com.spendroid.domain.Forecast.Event(today.plusDays(12), "MORTGAGE", -94_000, com.spendroid.domain.Forecast.Kind.BILL),
            9_000,
            com.spendroid.domain.Forecast.Event(today.plusDays(16), "FROM PERSONAL", 100_000, com.spendroid.domain.Forecast.Kind.TRANSFER_IN),
        )
        val voxi = base.rules.first { it.payee.contains("VOXI") }
        val rise = com.spendroid.domain.PriceChanges.Change(
            voxi.key, voxi.payee, 1_800, 2_200, today.minusDays(3), today.minusMonths(7), com.spendroid.domain.Cadence.MONTHLY,
            (7 downTo 1).map { com.spendroid.domain.PriceChanges.Payment(today.minusMonths(it.toLong()).minusDays(3), 1_800) } +
                com.spendroid.domain.PriceChanges.Payment(today.minusDays(3), 2_200),
        )
        val state = base.copy(outlook = outlook.copy(warnings = listOf(warning), priceChanges = mapOf(voxi.key to rise)))
        val which = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            when (which.intValue) {
                0 -> Frame(AppScreen.Home) {
                    HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {})
                }
                1 -> BudgetTheme { com.spendroid.ui.ForecastScreen(state, "acc-personal", onBack = {}) }
                2 -> Frame(AppScreen.Rules) {
                    com.spendroid.ui.RecurringRulesScreen(
                        rules = state.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {},
                        budget = state.budget, priceChanges = state.outlook.priceChanges,
                    )
                }
                3 -> Frame(AppScreen.Rules) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(14.dp)) { com.spendroid.ui.PriceHistory(rise, "GBP") }
                }
                4 -> Frame(AppScreen.Settings) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        com.spendroid.ui.BillsAndBalancesSection(onChanged = {})
                        com.spendroid.ui.PrivacySection(onConfirmLock = { it(true) }, onHideInRecents = {})
                    }
                }
                else -> BudgetTheme { com.spendroid.ui.LockScreen(onUnlock = {}) }
            }
        }
        listOf("outlook-home", "outlook-forecast", "outlook-regular", "outlook-history", "outlook-settings", "outlook-lock").forEachIndexed { i, name ->
            which.intValue = i
            compose.waitForIdle()
            // Regular opens on Upcoming; the price chips are on Bills.
            if (i == 2) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Bills")).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
                compose.waitForIdle()
            }
            compose.onRoot().captureRoboImage("build/screenshots/$name.png")
        }
    }
}
