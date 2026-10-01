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
            Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
                HeaderGlow(MaterialTheme.colorScheme.primary, height = 260.dp)
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = Charcoal.Text,
                    topBar = {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                            Wordmark(if (screen == AppScreen.Home) "SpenDroid" else screen.title, MaterialTheme.colorScheme.primary)
                        }
                    },
                    bottomBar = { PillNavigationBar(AppScreen.entries, screen) {} },
                ) { padding -> Box(Modifier.padding(padding)) { content() } }
            }
        }
    }

    @Test
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
    fun spending() {
        val state = Sample.state()
        shoot("spending", AppScreen.Spending) {
            com.spendroid.ui.SpendingScreen(
                state = state, onRefresh = {}, onToggleRecurring = {}, onToggleInternal = {}, onQueryChange = {},
                onAccountFilter = {}, onOverrideCategory = { _, _ -> }, onAlwaysCategorise = { _, _ -> },
                onMarkTransfer = { _, _ -> }, onMarkCardPayment = { _, _ -> }, onCategoryFilter = {}, onSetBudgetGoal = { _, _ -> },
            )
        }
    }

    @Test
    fun accounts() {
        val state = Sample.state()
        shoot("accounts", AppScreen.Accounts) {
            com.spendroid.ui.AccountManagementScreen(state = state, onLink = {}, onRelink = {}, onUpdateAccount = {})
        }
    }

    @Test
    fun rules() {
        val state = Sample.state()
        shoot("rules", AppScreen.Rules) {
            com.spendroid.ui.RecurringRulesScreen(rules = state.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {}, budget = state.budget)
        }
    }

    @Test
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
}
