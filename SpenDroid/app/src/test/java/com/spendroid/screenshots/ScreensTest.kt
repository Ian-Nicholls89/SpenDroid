package com.spendroid.screenshots

import com.spendroid.domain.toRecurringRule

import androidx.compose.ui.test.performClick
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
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

    /** Penny on the other pages too: the joint account's page, in its own pace. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun homeJoint() {
        val state = Sample.state()
        shoot("home-joint", AppScreen.Home) {
            HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {}, initialPage = 2)
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
                prompts = { com.spendroid.ui.PennyQuestionsStrip(3, {}, Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) },
            )
        }
    }

    /** Penny's question sheet over Home, as the app opens: [body] in the sheet. */
    @Composable
    private fun SheetOverHome(state: com.spendroid.ui.RootUiState, count: Int, body: @Composable () -> Unit) {
        Frame(AppScreen.Home) {
            Box {
                HomeScreen(
                    state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {},
                    prompts = { com.spendroid.ui.PennyQuestionsStrip(count, {}) },
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                androidx.compose.foundation.layout.Column(
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                        .background(Charcoal.Panel, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .padding(top = 20.dp),
                ) { body() }
            }
        }
    }

    private fun tesco(state: com.spendroid.ui.RootUiState): List<com.spendroid.data.db.SeenSpendEntity> {
        val nine = java.time.LocalDate.now().atTime(9, 5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun shop(id: String, at: Long) = com.spendroid.data.db.SeenSpendEntity(
            id = id, source = "com.google.android.apps.walletnfcrel", seenAt = at, amountMinor = -10_000, currency = "GBP",
            merchant = "TESCO STORES", cardDigits = "1234", accountId = state.accounts.first().id,
        )
        return listOf(shop("p1", nine), shop("p2", nine + 4 * 60_000))
    }

    /** 5.5: Penny's "once, or twice?" in her sheet over Home. Made-up figures. */
    @Test
    @Config(qualifiers = "w411dp-h900dp-xxhdpi")
    fun pennyQuestion() {
        val state = Sample.state()
        val questions = com.spendroid.domain.NotificationSpend.possibleRepeats(tesco(state)).map { com.spendroid.ui.PennyQuestion.Repeat(it) }
        shootRaw("penny-question") {
            SheetOverHome(state, questions.size + 2) {
                com.spendroid.ui.PennyQuestionBody(questions + questions + questions, state.accounts, emptyList(), com.spendroid.ui.PennyAnswers(), {}, {})
            }
        }
    }

    /** 5.5.2: the sheet with Penny at her highest - the "?" up, and after answering, mid-nod - nothing cut off. */
    @Test
    @Config(qualifiers = "w411dp-h900dp-xxhdpi")
    fun pennySheetReach() {
        val state = Sample.state()
        val questions = com.spendroid.domain.NotificationSpend.possibleRepeats(tesco(state)).map { com.spendroid.ui.PennyQuestion.Repeat(it) }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.spendroid.ui.LocalDroidAt provides 700L) {
                SheetOverHome(state, 2) {
                    com.spendroid.ui.PennyQuestionBody(questions + questions, state.accounts, emptyList(), com.spendroid.ui.PennyAnswers(), {}, {})
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/penny-sheet-reach.png")
    }

    /** 5.5: just answered - said back, with the way to change it. */
    @Test
    @Config(qualifiers = "w411dp-h900dp-xxhdpi")
    fun pennyAnswered() {
        val state = Sample.state()
        val questions = com.spendroid.domain.NotificationSpend.possibleRepeats(tesco(state)).map { com.spendroid.ui.PennyQuestion.Repeat(it) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SheetOverHome(state, 1) {
                com.spendroid.ui.PennyQuestionBody(questions, state.accounts, emptyList(), com.spendroid.ui.PennyAnswers(), {}, {})
            }
        }
        compose.onNodeWithText("Once, remove the extra").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/penny-answered.png")
    }

    /** 5.5: every kind of question Penny asks, as her sheet asks it. Made-up figures. */
    @Test
    @Config(qualifiers = "w2540dp-h900dp-xxhdpi")
    fun pennyQuestionKinds() {
        val state = Sample.state()
        val bank = "com.example.bank"
        val sources = listOf(com.spendroid.domain.NotificationSpend.Source(bank, "Example Bank", listOf("acc-personal", "acc-joint"), "acc-personal"))
        val week = System.currentTimeMillis() - 8 * 86_400_000L
        val which = com.spendroid.data.db.SeenSpendEntity(
            id = "seen-2", source = bank, seenAt = System.currentTimeMillis(), amountMinor = -1_240, currency = "GBP",
            merchant = "Boots", provisionalAccountId = "acc-personal",
        )
        val unreported = com.spendroid.data.db.SeenSpendEntity(
            id = "seen-3", source = bank, seenAt = week, amountMinor = -850, currency = "GBP", merchant = "Costa", accountId = "acc-personal",
        )
        val check = com.spendroid.data.db.SeenSpendEntity(
            id = "seen-4", source = bank, seenAt = System.currentTimeMillis(), amountMinor = -1, currency = "GBP", merchant = "City Car Park", accountId = "acc-personal",
        )
        val carPark = com.spendroid.domain.RecurringRule(
            key = "OUT|GBP|2|city car park", payee = "CITY CAR PARK", direction = com.spendroid.domain.Direction.OUT, amountMinor = -230,
            currency = "GBP", cadence = com.spendroid.domain.Cadence.QUARTERLY, anchorDay = 3,
            lastOccurrence = java.time.LocalDate.of(2026, 9, 3), occurrences = 2, score = 0.6f,
        )
        val kinds = listOf(
            com.spendroid.ui.PennyQuestion.NewBill(carPark),
            com.spendroid.ui.PennyQuestion.CardCheck(check),
            com.spendroid.ui.PennyQuestion.WhichAccount(which),
            com.spendroid.ui.PennyQuestion.NotReported(unreported),
            com.spendroid.ui.PennyQuestion.Vanished(Sample.questions().first()),
            com.spendroid.ui.PennyQuestion.Vanished(Sample.questions().first().copy(candidate = null)),
        )
        compose.setContent {
            BudgetTheme {
                androidx.compose.foundation.layout.Row(
                    Modifier.background(Charcoal.Background).padding(10.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    kinds.forEach { q ->
                        Box(Modifier.width(400.dp).background(Charcoal.Panel, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).padding(top = 20.dp)) {
                            com.spendroid.ui.PennyQuestionBody(listOf(q), state.accounts, sources, com.spendroid.ui.PennyAnswers(), {}, {})
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/penny-question-kinds.png")
    }

    /** 5.5: something Penny noticed, said once on Home in her line's place. Made-up digits. */
    @Test
    @Config(qualifiers = "w411dp-h900dp-xxhdpi")
    fun pennyNoticed() {
        val state = Sample.state()
        val news = com.spendroid.ui.PennyNews.News("digits:acc-nectar:0042", "I've learnt your Nectar Card ends 0042, so I can tell its payments apart now.")
        // At the top of her sparks, to show Home's pages leave room for them.
        shoot("penny-noticed", AppScreen.Home) {
            androidx.compose.runtime.CompositionLocalProvider(com.spendroid.ui.LocalDroidAt provides 1_250L) {
                HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {}, news = news)
            }
        }
    }

    /** 0.5.8: Penny's lines as stories - typed out, a stretching dot along the bubble's foot; the bubble the size of her longest line. */
    @Test
    @Config(qualifiers = "w411dp-h300dp-xxhdpi")
    fun pennyStories() {
        shootRaw("penny-stories") {
            BudgetTheme {
                androidx.compose.foundation.layout.Column(Modifier.background(Charcoal.Background).padding(20.dp)) {
                    com.spendroid.ui.PennySays(
                        com.spendroid.ui.DroidFace(com.spendroid.ui.DroidMood.AHEAD, 0.3f, 0.4f),
                        "That's four days in a row under your daily amount.",
                        onTap = {}, story = com.spendroid.ui.Story(1, 5, 0.2f, typing = true),
                        sizing = listOf("That's four days in a row under your daily amount.", "£9.38 so far today, all at Porton Stores. £48.39 of today's is left."),
                    )
                    com.spendroid.ui.PennySays(
                        com.spendroid.ui.DroidFace(com.spendroid.ui.DroidMood.AHEAD, 0.3f, 0.4f),
                        "£9.38 so far today, all at Porton Stores. £48.39 of today's is left.",
                        onTap = {}, story = com.spendroid.ui.Story(4, 5, 0.7f),
                    )
                }
            }
        }
    }

    private fun shootRaw(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
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

    /** 5.4: a PDF statement - the file as read, and Penny's verdict when it adds up, doesn't, or can't be checked. Made-up figures. */
    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun pdfImport() {
        val state = Sample.state()
        val d = java.time.LocalDate.of(2026, 9, 1)
        val tx = listOf(
            com.spendroid.importer.PdfStatement.Transaction(d, d.plusDays(1), "GREEN GROCER", null, -2_345, 1),
            com.spendroid.importer.PdfStatement.Transaction(d.plusDays(2), null, "SALARY", null, 185_000, 1),
            com.spendroid.importer.PdfStatement.Transaction(d.plusDays(4), null, "CORNER CAFE", null, -640, 2),
        )
        fun result(checks: List<com.spendroid.importer.PdfStatement.Check>, notes: List<String> = emptyList()) =
            com.spendroid.importer.PdfStatement.Result(com.spendroid.importer.PdfStatement.Kind.ACCOUNT, null, tx, checks, notes, listOf(listOf(com.spendroid.importer.PdfStatement.Seen("Date Description Out In Balance", "header"))), 312_015)
        val good = result(
            listOf(
                com.spendroid.importer.PdfStatement.Check("Brought forward", true, 130_000, 130_000, 1),
                com.spendroid.importer.PdfStatement.Check("Money in", true, 185_000, 185_000, 2),
                com.spendroid.importer.PdfStatement.Check("Money out", true, 2_985, 2_985, 2),
                com.spendroid.importer.PdfStatement.Check("Closing balance", true, 312_015, 312_015, 2),
            ),
        )
        val bad = result(
            listOf(
                com.spendroid.importer.PdfStatement.Check("Balance after CORNER CAFE", false, 312_015, 312_655, 2),
                com.spendroid.importer.PdfStatement.Check("Money out", false, 2_985, 2_345, 2),
                com.spendroid.importer.PdfStatement.Check("Closing balance", true, 312_015, 312_015, 2),
            ),
        )
        val none = result(emptyList(), listOf("Listed newest first - read and checked oldest first."))
        val parsed = com.spendroid.importer.StatementFile.fromPdf(good)
        val loaded = com.spendroid.ui.Loaded("statement-sep-2026.pdf", parsed)
        compose.setContent {
            Frame(AppScreen.Accounts) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    com.spendroid.ui.FileStep(loaded, null, state.accounts.take(2), state.accounts[1].id, {}, {})
                    com.spendroid.ui.Verdict(good)
                    com.spendroid.ui.Verdict(bad)
                    com.spendroid.ui.Verdict(none)
                    com.spendroid.ui.FileStep(null, "Penny doesn't know this layout yet - she couldn't find a table of transactions in letter.pdf.", emptyList(), null, {}, {}, unknownLayout = "--- page 1")
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/import-pdf.png")
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

    /** 4.6: subscriptions inside Regular - the filter, the tag, the yearly cost, and the sheet. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun subscriptionScreens() {
        val base = Sample.state()
        val subs = com.spendroid.domain.Subscriptions.all(base.rules, base.transactions, base.accounts, emptyMap(), java.time.LocalDate.now())
        val which = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            Frame(AppScreen.Rules) {
                com.spendroid.ui.RecurringRulesScreen(
                    rules = base.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {},
                    budget = base.budget, accounts = base.accounts, subscriptions = subs,
                    openRuleKey = if (which.intValue == 2) base.rules.first { it.payee.contains("PUREGYM") }.key else null,
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Bills")).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/subs-all.png")
        compose.onNode(androidx.compose.ui.test.hasText("Subscriptions", substring = true)).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/subs-filtered.png")
        which.intValue = 2
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/subs-sheet.png")
    }

    /** 4.6.2: a bill added by hand can be a subscription too. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun manualSubscription() {
        val base = Sample.state()
        val manual = com.spendroid.data.db.ManualRecurringRuleEntity(
            "av1", "Antivirus Pro", "OUT", 3_999, "GBP", "ANNUAL", 2, java.time.LocalDate.now().minusYears(1).plusDays(50).toString(), accountId = "acc-nectar",
        )
        val all = base.rules + listOfNotNull(manual.toRecurringRule())
        val subs = com.spendroid.domain.Subscriptions.all(all, base.transactions, base.accounts, emptyMap(), java.time.LocalDate.now())
        compose.setContent {
            Frame(AppScreen.Rules) {
                com.spendroid.ui.RecurringRulesScreen(
                    rules = base.rules, manualRules = listOf(manual), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {},
                    budget = base.budget, accounts = base.accounts, subscriptions = subs,
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Bills")).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("Antivirus Pro")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/manual-sub.png")
        compose.onNode(androidx.compose.ui.test.hasText("Subscriptions", substring = true)).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/manual-sub-filtered.png")
    }

    /** 4.7: contract end dates - the tags on Regular, the sheet, and the card on Home. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun contractScreens() {
        val base = Sample.state()
        val today = java.time.LocalDate.now()
        val ends = mapOf(
            com.spendroid.domain.Contracts.keyFor("VOXI MOBILE") to today.plusDays(23),
            com.spendroid.domain.Contracts.keyFor("BEANSTALK") to today.plusMonths(9),
            com.spendroid.domain.Contracts.keyFor("OCTOPUS ENERGY") to java.time.LocalDate.of(2026, 8, 1),
        )
        val contracts = com.spendroid.domain.Contracts.all(base.rules, base.transactions, ends, emptySet(), today)
        val state = base.copy(outlook = base.outlook.copy(contracts = contracts))
        val which = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            when (which.intValue) {
                0 -> Frame(AppScreen.Rules) {
                    com.spendroid.ui.RecurringRulesScreen(
                        rules = state.rules, manualRules = emptyList(), ignored = emptySet(), onToggle = { _, _ -> }, onAddManual = {},
                        budget = state.budget, accounts = state.accounts, contracts = contracts, transactions = state.transactions,
                        openRuleKey = null,
                    )
                }
                1 -> Frame(AppScreen.Rules) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(14.dp)) {
                        com.spendroid.ui.ContractSection(null, null, java.time.LocalDate.of(2025, 3, 14),
                            listOf(12 to java.time.LocalDate.of(2026, 3, 14), 18 to java.time.LocalDate.of(2026, 9, 14), 24 to java.time.LocalDate.of(2027, 3, 14)), {}, {})
                        com.spendroid.ui.ContractSection(today.plusDays(23), contracts.values.first { it.status is com.spendroid.domain.Contracts.Status.EndingSoon }.status, null, emptyList(), {}, {})
                    }
                }
                else -> Frame(AppScreen.Home) {
                    HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Bills")).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/contract-regular.png")
        which.intValue = 1
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/contract-sheet.png")
        which.intValue = 2
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/contract-home.png")
    }

    /** 4.8: "which account?" pinned on Home, and the choice in Card alerts. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun accountAttribution() {
        val state = Sample.state()
        val bank = "com.example.bank"
        val sources = listOf(com.spendroid.domain.NotificationSpend.Source(bank, "Example Bank", listOf("acc-personal", "acc-joint"), "acc-personal"))
        val waiting = com.spendroid.data.db.SeenSpendEntity(
            id = "seen-2", source = bank, seenAt = System.currentTimeMillis(), amountMinor = -1_240, currency = "GBP",
            merchant = "Co-op", provisionalAccountId = "acc-personal",
        )
        val step = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            if (step.intValue == 0) {
                SheetOverHome(state, 1) {
                    com.spendroid.ui.PennyQuestionBody(
                        listOf(com.spendroid.ui.PennyQuestion.WhichAccount(waiting)), state.accounts, sources, com.spendroid.ui.PennyAnswers(), {}, {},
                    )
                }
            } else {
                Frame(AppScreen.Settings) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(14.dp)) {
                        com.spendroid.ui.CardAlertsSection(
                            accounts = state.accounts, sources = sources, readingOn = true, samples = emptyList(),
                            onSaveSources = {}, onReadingOn = {}, onUpdateAccount = {}, onClear = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/which-account-home.png")
        step.intValue = 1
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/card-alerts-unsure.png")
    }

    /** 4.8: "Paid from" on a payment read from a notification. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun paidFrom() {
        val state = Sample.state()
        val spend = com.spendroid.data.db.SeenSpendEntity(
            id = "seen-2", source = "com.example.bank", seenAt = System.currentTimeMillis(), amountMinor = -1_240, currency = "GBP",
            merchant = "Co-op", accountId = "acc-joint", accountGuessed = true,
        )
        compose.setContent {
            BudgetTheme {
                com.spendroid.ui.TransactionDetailSheet(
                    transaction = com.spendroid.domain.NotificationSpend.asTransaction(spend, "Example Bank"),
                    userRules = emptyList(), onDismiss = {}, onOverrideCategory = { _, _ -> },
                    onAlwaysCategorise = { _, _ -> }, onMarkTransfer = { _, _ -> }, accountLabel = "Joint Account",
                    paidFrom = com.spendroid.ui.PaidFrom(
                        choices = state.accounts.filter { it.id == "acc-personal" || it.id == "acc-joint" },
                        currentId = "acc-joint", how = "Penny guessed this", always = false, onChoose = { _, _ -> },
                        allAccounts = state.accounts,
                    ),
                )
            }
        }
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/paid-from.png")
    }

    /** 4.9: a manual account on Accounts, with the reminders, and Home's "how current" line. */
    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun manualAccounts() {
        val base = Sample.state()
        val today = java.time.LocalDate.now()
        val savings = com.spendroid.data.db.AccountEntity(
            "manual:1", "Example Bank", "Rainy Day", "GBP", 250_000, 0L, com.spendroid.data.db.AccountType.SAVINGS,
            manual = true, anchorMinor = 250_000, anchorDate = today.minusDays(9).toString(),
            checkedAt = System.currentTimeMillis() - 9 * 86_400_000L,
        )
        val state = base.copy(accounts = base.accounts + savings, manualCoverage = mapOf(savings.id to today.minusDays(9)))
        val step = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            if (step.intValue == 0) {
                Frame(AppScreen.Accounts) {
                    com.spendroid.ui.AccountManagementScreen(state = state, onLink = {}, onRelink = {}, onUpdateAccount = {})
                }
            } else {
                Frame(AppScreen.Home) {
                    HomeScreen(
                        state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {},
                        prompts = { com.spendroid.ui.ManualFreshness(state, {}, {}) },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/manual-accounts.png")
        step.intValue = 1
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/manual-home.png")
    }

    /** 5.0: every step of first-run setup, from made-up accounts. */
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun setupSteps() {
        val base = Sample.state()
        val manual = base.accounts.filter { it.id == "acc-personal" || it.id == "acc-joint" }
            .map { it.copy(manual = true, institutionName = "Example Bank", anchorMinor = it.balanceMinor, anchorDate = java.time.LocalDate.now().minusDays(3).toString()) }
        val state = base.copy(
            accounts = manual + base.accounts.filter { it.id != "acc-personal" && it.id != "acc-joint" },
            manualCoverage = mapOf("acc-personal" to java.time.LocalDate.now().minusDays(3)),
            spendSources = listOf(com.spendroid.domain.NotificationSpend.Source("com.example.bank", "Example Bank", listOf("acc-personal", "acc-joint"), "acc-personal")),
        )
        val step = androidx.compose.runtime.mutableStateOf(com.spendroid.ui.SetupStep.WELCOME)
        compose.setContent {
            BudgetTheme {
                androidx.compose.runtime.key(step.value) {
                    com.spendroid.ui.SetupFlow(
                        state = if (step.value == com.spendroid.ui.SetupStep.ACCOUNTS || step.value == com.spendroid.ui.SetupStep.WELCOME) state else state,
                        onAddAccount = { _, _, _, _ -> }, onRemoveAccount = {}, onStartFromToday = { _, _, _, _ -> }, onAddPay = { _, _ -> },
                        onSetBalance = { _, _ -> }, onIgnoreRule = { _, _ -> }, onSaveSources = {}, onUpdateAccount = {},
                        onReload = {}, onFinish = {}, gocardless = {}, importScreen = { _, _ -> },
                        initialStep = step.value, accessGranted = false,
                    )
                }
            }
        }
        listOf(
            com.spendroid.ui.SetupStep.WELCOME, com.spendroid.ui.SetupStep.START, com.spendroid.ui.SetupStep.ACCOUNTS, com.spendroid.ui.SetupStep.HISTORY,
            com.spendroid.ui.SetupStep.FOUND, com.spendroid.ui.SetupStep.FRESH, com.spendroid.ui.SetupStep.NOTIFY,
            com.spendroid.ui.SetupStep.APPS, com.spendroid.ui.SetupStep.REMINDERS, com.spendroid.ui.SetupStep.DONE,
        ).forEach { s ->
            step.value = s
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/screenshots/setup-${s.name.lowercase()}.png")
        }
    }

    @Test
    fun payAnswers() {
        val today = java.time.LocalDate.of(2026, 10, 18) // a Sunday
        val c = com.spendroid.domain.Cadence.MONTHLY
        // A set date still to come this month: last month's.
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 9, 25), com.spendroid.ui.payAnswer(c, 25, null, 2_340_00, today)!!.lastPaid)
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 10, 15), com.spendroid.ui.payAnswer(c, 15, null, 2_340_00, today)!!.lastPaid)
        // Last working day: September's was Wednesday the 30th.
        val lwd = com.spendroid.ui.payAnswer(com.spendroid.domain.Cadence.MONTHLY_LAST_BUSINESS_DAY, null, null, 2_340_00, today)!!
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 9, 30), lwd.lastPaid)
        // Weekly needs the day; nothing without pay.
        org.junit.Assert.assertNull(com.spendroid.ui.payAnswer(com.spendroid.domain.Cadence.WEEKLY, null, null, 50_000, today))
        org.junit.Assert.assertNull(com.spendroid.ui.payAnswer(c, 25, null, null, today))
    }

    /** 5.1: the droid's screens - Today, Payday, the cycle review - and the colour choice. */
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun identityScreens() {
        val state = Sample.state()
        val review = com.spendroid.domain.CycleReview(
            java.time.LocalDate.of(2026, 9, 2), java.time.LocalDate.of(2026, 9, 29), "GBP", 11_200, 160_000,
            daily = List(28) { java.time.LocalDate.of(2026, 9, 2).plusDays(it.toLong()) to listOf(4_000L, 6_600L, 3_000L, 9_500L, 5_200L, 11_000L, 2_000L)[it % 7] },
            dailyShareMinor = 6_000, daysAhead = 22, biggestDay = java.time.LocalDate.of(2026, 9, 12) to 11_200L, mostVisited = "PRET A MANGER" to 11,
        )
        val step = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            BudgetTheme(accent = if (step.intValue == 3) com.spendroid.ui.theme.Accent.MINT else com.spendroid.ui.theme.Accent.GREEN) {
                when (step.intValue) {
                    0 -> com.spendroid.ui.TodayScreen(state, onBack = {})
                    1 -> com.spendroid.ui.PaydayScreen(state, review, onReview = {}, onDone = {})
                    2 -> com.spendroid.ui.ReviewScreen(review, loading = false, onBack = {})
                    else -> Frame(AppScreen.Home) {
                        HomeScreen(state, onRefresh = {}, onRelink = {}, onSeeAllTransactions = {}, onSetBudgetGoal = { _, _ -> }, onLinkBank = {})
                    }
                }
            }
        }
        listOf("today", "payday", "review", "home-mint").forEachIndexed { i, name ->
            step.intValue = i
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/screenshots/identity-$name.png")
        }
    }
}
