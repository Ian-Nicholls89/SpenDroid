package com.spendroid.wear.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.android.gms.wearable.DataMap
import com.spendroid.wear.BudgetReading
import com.spendroid.wear.BudgetScreens
import com.spendroid.wear.Roundup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The watch screens from made-up figures, round and at a large font, as on the user's watch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w228dp-h228dp-round-xhdpi", application = android.app.Application::class)
class WatchScreensTest {

    @get:Rule val compose = createComposeRule()

    private fun reading() = BudgetReading(
        DataMap().apply {
            putString("available", "£312"); putString("availableFull", "£312.40"); putFloat("budgetLeft", 0.62f)
            putFloat("cycleLeft", 0.75f); putString("pace", "ON_TRACK"); putInt("accent", 0xFFF5A623.toInt())
            putString("untilLine", "left until Fri 23 Oct"); putString("perDay", "£14.20 a day"); putString("dayOfCycle", "day 7 of 28")
            putFloat("used", 0.38f); putFloat("elapsed", 0.25f); putString("barLabel", "£191 / £504")
            putString("daysLine", "22 days · £14.20 a day"); putString("fromAccount", "£150 from your account")
            // With cards (phone 0.8.7): the month leads, the account a page down.
            putBoolean("month", true); putString("leftLabel", "left this month"); putString("fromAccountShort", "£150 from account")
            putFloat("splitAccount", 0.24f); putFloat("splitCards", 0.14f)
            putString("acctAvailable", "£150"); putString("acctAvailableFull", "£150.20"); putFloat("acctUsed", 0.55f); putFloat("acctElapsed", 0.25f)
            putString("acctPace", "TIGHT"); putString("acctDaysLine", "22 days · £6.82 a day")
            putString("acctStatements", "after £594 of card bills"); putString("acctNote", "the rest of the month on cards")
            putStringArrayList("weekShort", arrayListOf("£12", "£24", "£8", "£41", "£15", "£28", "£21")); putString("weekTotal", "£149.05")
            putString("todayTotal", "£21.05"); putString("todayPending", "£4.25 pending"); putString("todayAccounts", "£8.75"); putString("todayCards", "£12.30")
            putLong("todayAccountsMinor", 875); putLong("todayCardsMinor", 1230); putInt("potColour", 0xFF5A5FD8.toInt()); putInt("cardsColour", 0xFF9B5DE5.toInt())
            putLongArray("week", longArrayOf(1200, 2400, 800, 4100, 1500, 2800, 2105)); putString("weekEnds", "2026-10-01"); putString("updated", "Updated 13:52")
            putString("upcomingTotal", "£142.99")
            putDataMapArrayList("upcoming", arrayListOf(
                DataMap().apply { putString("name", "PUREGYM"); putString("date", "5 Oct"); putString("amount", "£24.99"); putString("short", "£24"); putInt("colour", 0xFF7A3BE0.toInt()) },
                DataMap().apply { putString("name", "OCTOPUS ENERGY"); putString("date", "8 Oct"); putString("amount", "£96.00"); putString("short", "about £96"); putInt("colour", 0xFFE05A3B.toInt()) },
                DataMap().apply { putString("name", "Nectar Card"); putString("date", "~17 Oct"); putString("amount", "£212.08"); putString("short", "£212"); putBoolean("card", true); putInt("colour", 0xFF9B5DE5.toInt()) },
            ))
            putDataMapArrayList("recent", arrayListOf(
                DataMap().apply { putString("id", "a"); putString("payee", "GREGGS"); putString("amount", "£4.25"); putString("category", "Eating out"); putInt("colour", 0xFFD81B60.toInt()); putBoolean("pending", true); putString("day", "Today") },
                DataMap().apply { putString("id", "b"); putString("payee", "PORTON STORES"); putString("amount", "£4.50"); putString("category", "Work lunches"); putInt("colour", 0xFF827717.toInt()); putString("day", "Today") },
                DataMap().apply { putString("id", "c"); putString("payee", "TRAINLINE"); putString("amount", "£12.80"); putString("category", "Transport"); putInt("colour", 0xFF1E88E5.toInt()); putString("day", "Yesterday") },
            ))
            putDataMapArrayList("categories", arrayListOf(
                DataMap().apply { putString("label", "Work lunches"); putString("spent", "£84.20"); putInt("colour", 0xFF827717.toInt()); putLong("minor", 8420) },
                DataMap().apply { putString("label", "Groceries"); putString("spent", "£61.10"); putInt("colour", 0xFF43A047.toInt()); putLong("minor", 6110) },
                DataMap().apply { putString("label", "Family"); putString("spent", "£42.80"); putInt("colour", 0xFF00838F.toInt()); putLong("minor", 4280) },
                DataMap().apply { putString("label", "Transport"); putString("spent", "£25.60"); putInt("colour", 0xFF1E88E5.toInt()); putLong("minor", 2560) },
            ))
            putDataMapArrayList("cards", arrayListOf(DataMap().apply {
                putString("name", "Nectar Card"); putString("since", "£138.69"); putString("capShort", "£580"); putFloat("capShare", 0.24f)
                putFloat("statementGone", 0.3f); putString("closes", "closes 23 Oct"); putString("nextBill", "bill £212.08 · 17 Oct"); putInt("colour", 0xFF9B5DE5.toInt())
            }))
        },
    )

    private fun shoot(name: String, page: Int) {
        RuntimeEnvironment.setFontScale(1.15f)
        compose.setContent { BudgetScreens(reading(), onOpenPhone = { true }, initialPage = page) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun hero() = shoot("1-hero", 0)
    @Test fun account() = shoot("1b-account", 1)
    @Test fun today() = shoot("2-today", 2)
    @Test fun categories() = shoot("6-categories", 3)
    @Test fun recent() = shoot("5-recent", 4)
    @Test fun week() = shoot("week", 5)
    @Test fun card() = shoot("3-card", 6)
    @Test fun upcoming() = shoot("4-upcoming", 7)

    @Test
    fun roundup() {
        RuntimeEnvironment.setFontScale(1.15f)
        val r = Roundup(1L, "£303.65 left", "ON_TRACK", listOf("Spent today £17.50", "Income Fri 23 Oct · £13.80 a day", "Tomorrow: PUREGYM £24.99", "2 transactions to check on your phone"))
        compose.setContent { BudgetScreens(reading(), onOpenPhone = { true }, roundup = r) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/7-roundup.png")
    }
}
