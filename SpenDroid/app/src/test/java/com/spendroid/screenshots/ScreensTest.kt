package com.spendroid.screenshots

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
}
