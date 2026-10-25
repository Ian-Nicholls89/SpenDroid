package com.spendroid.wear.app

import com.google.android.gms.wearable.DataMap
import com.spendroid.wear.BudgetReading

/** Made-up figures, as the phone sends them: the watch screenshots' own. */
object SampleReading {
    fun reading(available: String = "£312") = BudgetReading(
        DataMap().apply {
            putString("available", available); putString("availableFull", "$available.40"); putFloat("budgetLeft", 0.62f)
            putFloat("cycleLeft", 0.75f); putString("pace", "ON_TRACK"); putInt("accent", 0xFFF5A623.toInt())
            putString("untilLine", "left until Fri 23 Oct"); putString("perDay", "£14.20 a day"); putString("dayOfCycle", "day 7 of 28")
            putFloat("used", 0.38f); putFloat("elapsed", 0.25f); putString("barLabel", "£191 / £504")
            putString("daysLine", "22 days · £14.20 a day")
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
}
