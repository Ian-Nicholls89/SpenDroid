package com.spendroid.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.DuplicateCheckEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.DuplicateCheck
import com.spendroid.widget.SharedSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 0.8.5: the same figures for less work. Bills are found once per history, the cycle review
 * reads its settings once, the widgets draw from what the app has just worked out, and
 * calculations read rows without the bank's raw record - which the backup still has.
 * Made-up names and figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EfficiencyTest {

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()

    private fun tx(acc: String, id: String, date: LocalDate, minor: Long, payee: String, raw: String? = null) = TransactionEntity(
        accountId = acc, transactionId = DuplicateCheck.IMPORTED_PREFIX + id, bookingDate = date.toString(),
        valueDate = null, amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = raw,
    )

    /** Four paydays, monthly bills and some spending, on one account. */
    private suspend fun household(): String {
        app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        com.spendroid.data.Choices.awaitLoaded()
        val repo = app.repository
        val account = repo.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
        val today = LocalDate.now()
        val rows = (0..3L).flatMap { m ->
            val first = today.minusMonths(m).withDayOfMonth(1)
            listOf(
                tx(account.id, "pay$m", first, 200_000, "MADE-UP EMPLOYER LTD", raw = """{"made":"up $m"}"""),
                tx(account.id, "gym$m", first.plusDays(2), -2_499, "MADE-UP GYM"),
                tx(account.id, "shop$m", first.plusDays(4), -3_120, "MADE-UP GROCER"),
            )
        }
        repo.addImported(rows, closing = today to 120_000L)
        return account.id
    }

    @Test
    fun `calculations read rows without the bank's record, and the backup still has it`() = runBlocking {
        val id = household()
        val repo = app.repository
        val pay = DuplicateCheck.IMPORTED_PREFIX + "pay0"
        assertNull(repo.transactions().first { it.transactionId == pay }.rawJson)
        assertEquals("""{"made":"up 0"}""", repo.storedTransactions(id).first { it.transactionId == pay }.rawJson)
    }

    @Test
    fun `bills are found once for the same history, and again when it changes`() = runBlocking {
        household()
        val repo = app.repository
        val all = repo.transactions()
        val first = repo.detectedRules(all)
        assertTrue("the gym is found as a bill", first.any { it.payee.contains("GYM") })
        // A fresh read of the same rows is the same history.
        assertSame(first, repo.detectedRules(repo.transactions()))
        val changed = all.drop(1)
        assertNotSame(first, repo.detectedRules(changed))
        assertEquals(com.spendroid.domain.RecurringAnalyzer.analyze(changed), repo.detectedRules(changed))
    }

    @Test
    fun `the budget is the same however it is reached`() = runBlocking {
        household()
        val repo = app.repository
        val all = repo.transactions()
        val time = repo.budgetTime()
        val direct = repo.budgetSnapshotOf(all, time)
        // Worked out again with the bills already found: the same answer.
        assertEquals(direct, repo.budgetSnapshotOf(repo.transactions(), time))
        assertEquals(direct, repo.budgetSnapshot(time))
    }

    @Test
    fun `the cycle review agrees with the budget as the cycle ended`() = runBlocking {
        household()
        val repo = app.repository
        val review = repo.lastCycleReview()
        org.junit.Assert.assertNotNull("a whole cycle to look back on", review)
        review!!
        val all = repo.transactions()
        val last = repo.budgetSnapshotOf(all, review.end.atTime(23, 59))
        assertEquals(last.availableToSpend - last.shortfallMinor, review.leftOverMinor)
        assertEquals(last.spentThisCycle, review.spentMinor)
        val days = review.daily.map { it.first }
        assertEquals(review.daily.map { it.second }, days.map { repo.budgetSnapshotOf(all, it.atTime(23, 59)).spentToday })
    }

    @Test
    fun `the widgets draw from the budget and history the app just loaded`() = runBlocking {
        household()
        val repo = app.repository
        val all = repo.transactions()
        val budget = repo.budgetSnapshotOf(all, repo.budgetTime())
        SharedSnapshot.hold(budget, all)
        assertSame(budget, SharedSnapshot.get(app))
        assertSame(all, SharedSnapshot.transactions(app))
    }

    @Test
    fun `confirming a duplicate keeps the bank's record of the row that stays`() = runBlocking {
        val id = household()
        val repo = app.repository
        val today = LocalDate.now()
        val old = tx(id, "old", today, -999, "MADE-UP CAFE")
        val new = tx(id, "new", today, -999, "MADE-UP CAFE", raw = """{"bank":"new"}""")
        repo.addImported(listOf(old, new))
        // As the screen has it: read for calculation, so without the record.
        val candidate = repo.transactions().first { it.transactionId == new.transactionId }
        assertNull(candidate.rawJson)
        repo.confirmDuplicate(DuplicateCheck.Question(DuplicateCheckEntity(id, old.transactionId, 2, new.transactionId, DuplicateCheck.State.ASK.name), old, candidate))
        val kept = repo.storedTransactions(id)
        assertTrue(kept.none { it.transactionId == old.transactionId })
        assertEquals("""{"bank":"new"}""", kept.first { it.transactionId == new.transactionId }.rawJson)
    }
}
