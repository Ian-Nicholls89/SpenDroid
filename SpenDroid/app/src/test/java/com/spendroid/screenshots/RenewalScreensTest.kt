package com.spendroid.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.spendroid.AppScreen
import com.spendroid.PillNavigationBar
import com.spendroid.domain.Renewals
import com.spendroid.domain.TabVoice
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
import java.time.LocalDate

/**
 * 0.7.2: renewals as they render - Penny's amber bubble on Home and Regular, the Renewing card,
 * the tab, and her question. Made-up names and figures. Run with -Pscreenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi", application = android.app.Application::class)
class RenewalScreensTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.now()
    private val car = Renewals.Renewal(
        id = "car", name = "Car insurance", ends = today.plusWeeks(6), lastMinor = 46_218,
        renewedBy = listOf(
            Renewals.Payment("a", today.plusWeeks(6).minusYears(1).minusDays(12), "ADMIRAL INSURANCE", 46_218),
            Renewals.Payment("b", today.plusWeeks(6).minusYears(2).minusDays(10), "AVIVA", 43_120),
        ),
    )
    private val others = listOf(
        Renewals.Renewal(id = "bd", name = "Breakdown cover", ends = today.plusMonths(7), paid = Renewals.Paid.MONTHLY),
        Renewals.Renewal(id = "home", name = "Home insurance", ends = today.plusMonths(9), lastMinor = 21_000),
        Renewals.Renewal(id = "tv", name = "TV licence", ends = today.plusMonths(11), paid = Renewals.Paid.MONTHLY),
    )
    private val items = Renewals.all(listOf(car) + others, emptyList(), today)
    private val lines = TabVoice.renewalLines(items)

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
                                Wordmark(if (screen == AppScreen.Home) "SpenDroid" else screen.title, glow.colour ?: MaterialTheme.colorScheme.primary, droid = screen == AppScreen.Settings)
                            }
                        },
                        bottomBar = { PillNavigationBar(AppScreen.entries, screen) {} },
                    ) { padding -> Box(Modifier.padding(padding)) { content() } }
                }
            }
        }
    }

    @Test
    fun home() {
        val state = Sample.state().copy(renewals = items)
        compose.setContent {
            Frame(AppScreen.Home) {
                HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {}, needsYou = lines)
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/renewals-home.png")
    }

    @Composable
    private fun Regular(renewals: List<Renewals.Item>) {
        val state = Sample.state()
        val tab = TabVoice.regular(state.budget!!, state.outlook, { com.spendroid.ui.formatMoney(it, "GBP") }, renewals)
        Frame(AppScreen.Rules) {
            com.spendroid.ui.RecurringRulesScreen(
                rules = state.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {},
                budget = state.budget, renewals = renewals,
                penny = {
                    com.spendroid.ui.PennyStories(
                        com.spendroid.ui.droidFaceOf(state.budget, false), tab.map { it.text },
                        actions = tab.map { l -> l.target?.let { com.spendroid.ui.linkLabel(it) to {} } },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        needsYou = tab.map { it.needsYou },
                    )
                },
            )
        }
    }

    @Test
    fun regularUpcoming() {
        compose.setContent { Regular(items) }
        compose.onRoot().captureRoboImage("build/screenshots/renewals-upcoming.png")
    }

    @Test
    fun regularTab() {
        compose.setContent { Regular(items) }
        compose.onNodeWithText("Renewals").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/renewals-tab.png")
    }

    @Test
    fun regularEmpty() {
        compose.setContent { Regular(emptyList()) }
        compose.onNodeWithText("Renewals").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/renewals-empty.png")
    }

    /** Her question, both steps: was it, then when the new cover ends. */
    @Test
    @Config(qualifiers = "w840dp-h760dp-xxhdpi")
    fun question() {
        val state = Sample.state()
        val payment = Renewals.Payment("p", today.minusDays(3), "ADMIRAL INSURANCE", 48_910)
        val q = com.spendroid.ui.PennyQuestion.Renewed(car.copy(ends = today.plusDays(9)), payment)
        compose.setContent {
            BudgetTheme {
                Row(Modifier.background(Charcoal.Background).padding(10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    repeat(2) { i ->
                        Box(Modifier.width(400.dp).background(Charcoal.Panel, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).padding(top = 20.dp)) {
                            androidx.compose.runtime.key(i) {
                                com.spendroid.ui.PennyQuestionBody(listOf(q), state.accounts, emptyList(), com.spendroid.ui.PennyAnswers(), {}, {})
                            }
                        }
                    }
                }
            }
        }
        // The second panel on to its next step.
        compose.onAllNodes(androidx.compose.ui.test.hasText("Yes, renewed"))[1].performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/renewals-question.png")
    }

    // ---- 0.7.3: Regular in three tabs, Settings as one list ----

    @Test
    fun regularLeftOut() {
        val state = Sample.state()
        val ignored = state.rules.filter { it.direction == com.spendroid.domain.Direction.OUT }.take(1).map { it.key }.toSet()
        compose.setContent {
            Frame(AppScreen.Rules) {
                com.spendroid.ui.RecurringRulesScreen(
                    rules = state.rules, manualRules = emptyList(), ignored = ignored, onToggle = { _, _ -> }, onAddManual = {}, budget = state.budget,
                )
            }
        }
        compose.onNode(androidx.compose.ui.test.hasText("Bills") and androidx.compose.ui.test.hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Left out", substring = true).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/tidy-left-out.png")
    }

    @Test
    fun regularIncome() {
        val state = Sample.state()
        compose.setContent {
            Frame(AppScreen.Rules) {
                com.spendroid.ui.RecurringRulesScreen(
                    rules = state.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {}, budget = state.budget,
                    primaryIncomeKey = state.rules.firstOrNull { it.direction == com.spendroid.domain.Direction.IN }?.key,
                )
            }
        }
        compose.onNodeWithText("Income ›", substring = true).performClick()
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/tidy-income.png")
    }

    @Test
    fun settingsList() {
        val state = Sample.state()
        compose.setContent {
            Frame(AppScreen.Settings) {
                com.spendroid.ui.SettingsScreen(
                    state = state, onSave = { _, _ -> }, onSetBudgetModel = {}, onSetCardTiming = {}, onExport = {}, onImport = {},
                    onClearData = {}, onCheckUpdate = {}, onOpenInstallSettings = {}, secretId = "abc", secretKey = "def",
                    notificationTime = "19:30", onSaveNotificationTime = {}, cardAlertsStatus = "Reading NatWest · Wallet off",
                )
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/tidy-settings.png")
        compose.onNodeWithText("Privacy & security").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/tidy-settings-privacy.png")
    }
}
