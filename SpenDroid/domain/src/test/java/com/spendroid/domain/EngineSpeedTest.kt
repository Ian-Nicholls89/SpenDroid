package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A guard on speed: two years of a made-up household (about 4,000 rows) through bill finding
 * and the budget. Each takes well under 100ms on a desktop; the limits are many times that, so
 * only a step change - something quadratic creeping in - fails it, not a slow machine.
 */
class EngineSpeedTest {

    private val history: List<TransactionEntity> = run {
        var seq = 0
        fun tx(a: String, d: LocalDate, amt: Long, p: String) = TransactionEntity(a, "t${seq++}", d.toString(), null, amt, "GBP", p, null, false, null)
        val shops = listOf("MADE-UP GROCER", "MADE-UP CAFE", "MADE-UP BAKERY", "MADE-UP FUEL", "MADE-UP CHEMIST", "MADE-UP BOOKS")
        val bills = listOf("MADE-UP ENERGY" to 9_600L, "MADE-UP GYM" to 2_499L, "MADE-UP STREAMING" to 1_099L, "MADE-UP COUNCIL" to 18_000L, "MADE-UP WATER" to 4_200L)
        val rnd = java.util.Random(1)
        buildList {
            var d = LocalDate.of(2024, 11, 1)
            while (!d.isAfter(LocalDate.of(2026, 11, 3))) {
                if (d.dayOfMonth == 25) add(tx("p", d, 250_000, "MADE-UP EMPLOYER"))
                bills.forEachIndexed { i, (n, a) -> if (d.dayOfMonth == 1 + i * 3) add(tx("p", d, -a, n)) }
                if (d.dayOfMonth == 15) { add(tx("p", d, -60_000, "MADE-UP CARD CO")); add(tx("c", d, 60_000, "DIRECT DEBIT PAYMENT")) }
                repeat(2) { add(tx("p", d, -(200L + rnd.nextInt(3_000)), shops[rnd.nextInt(shops.size)])) }
                repeat(3) { add(tx("c", d, -(200L + rnd.nextInt(4_000)), shops[rnd.nextInt(shops.size)])) }
                d = d.plusDays(1)
            }
        }
    }

    private val accounts = listOf(
        AccountEntity("p", "Made-up Bank", "Everyday", "GBP", 180_000, 0L, AccountType.PERSONAL),
        AccountEntity("c", "Made-up Card Co", "Card", "GBP", -60_000, 0L, AccountType.CREDIT_CARD),
    )

    private fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test
    fun `two years of history is found and budgeted quickly`() {
        repeat(2) { RecurringAnalyzer.analyze(history) }
        val rules = RecurringAnalyzer.analyze(history)
        val now = LocalDateTime.of(2026, 11, 3, 12, 0)
        val analyze = (1..3).minOf { ms { RecurringAnalyzer.analyze(history) } }
        val budget = (1..3).minOf { ms { BudgetEngine.snapshot(transactions = history, rules = rules, accounts = accounts, referenceTime = now) } }
        assertTrue("finding bills took ${analyze}ms over ${history.size} rows", analyze < 1_000)
        assertTrue("the budget took ${budget}ms over ${history.size} rows", budget < 1_500)
    }
}
