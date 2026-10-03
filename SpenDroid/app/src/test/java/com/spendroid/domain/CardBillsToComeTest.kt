package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.1: a regular payment made on a card is the card's, counted in its bill and shown on the card
 * from the statement's first day; one that moves between accounts follows its latest payment;
 * and carrying over sets quarterly and yearly bills aside as they approach.
 */
class CardBillsToComeTest {

    private var seq = 0
    private fun tx(account: String, date: String, minor: Long, payee: String) = TransactionEntity(
        accountId = account, transactionId = "t${seq++}", bookingDate = date, valueDate = null,
        amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )

    private val personal = AccountEntity("Personal", "bank", "Personal", "GBP", 150_000, 0, AccountType.PERSONAL)
    private val nectar = AccountEntity("Nectar", "bank", "Nectar", "GBP", -20_000, 0, AccountType.CREDIT_CARD)
        .copy(statementDayOfMonth = 23, paymentDayOfMonth = 17)
    private val accounts = listOf(personal, nectar)
    private val today = LocalDate.of(2026, 9, 28)

    private val salary = listOf("2026-06-25", "2026-07-25", "2026-08-25", "2026-09-25").map { tx("Personal", it, 250_000, "ACME") }
    private val cardShop = tx("Nectar", "2026-09-10", -20_000, "BIG SHOP")

    private fun tumbletots(account: (Int) -> String) =
        (6..9).map { m -> tx(account(m), "2026-%02d-01".format(m), -6_125, "TUMBLETOTS.COM SALISBURY ENG GBR 0218") }

    private fun snapshot(all: List<TransactionEntity>, manual: List<ManualRecurringRuleEntity> = emptyList(), model: BudgetModel = BudgetModel.FRESH_START) =
        BudgetEngine.snapshot(
            all,
            RecurringAnalyzer.analyze(all) + manual.mapNotNull { it.toRecurringRule() },
            accounts,
            today.atTime(12, 0),
            budgetModel = model,
        )

    @Test
    fun `a bill paid on the card is not a bill from your income`() {
        val s = snapshot(salary + cardShop + tumbletots { "Nectar" })
        assertFalse(s.fixedRules.any { it.payee.startsWith("TUMBLETOTS") })
        assertFalse(s.upcomingFixed.any { it.rule.payee.startsWith("TUMBLETOTS") })
    }

    @Test
    fun `the card shows it to come from the statement's first day`() {
        val s = snapshot(salary + cardShop + tumbletots { "Nectar" })
        val bill = s.cardBills.single()
        assertEquals(6_125L, bill.toComeMinor)
        assertEquals(LocalDate.of(2026, 10, 1), bill.toCome.single().dueDate)
        // The projection includes it, so the card is not a surprise when it lands.
        assertTrue((bill.projectedMinor ?: 0L) >= bill.unbilledMinor + 6_125L)
    }

    @Test
    fun `a bill that moved to the card follows its latest payment`() {
        val moved = tumbletots { m -> if (m < 9) "Personal" else "Nectar" }
        assertFalse(snapshot(salary + cardShop + moved).fixedRules.any { it.payee.startsWith("TUMBLETOTS") })
        val back = tumbletots { m -> if (m < 9) "Nectar" else "Personal" }
        assertTrue(snapshot(salary + cardShop + back).fixedRules.any { it.payee.startsWith("TUMBLETOTS") })
    }

    @Test
    fun `a bill made by hand on the card is counted once, in the card's bill`() {
        fun manual(account: String?) = ManualRecurringRuleEntity(
            id = "m1", payee = "INSURANCE", direction = "OUT", amountMinor = 3_000, currency = "GBP",
            cadence = "MONTHLY", anchorDay = 5, startDate = "2026-09-05", accountId = account,
        )
        assertFalse(snapshot(salary + cardShop, listOf(manual("Nectar"))).fixedRules.any { it.payee == "INSURANCE" })
        assertTrue(snapshot(salary + cardShop, listOf(manual("Personal"))).fixedRules.any { it.payee == "INSURANCE" })
        assertTrue(snapshot(salary + cardShop, listOf(manual(null))).fixedRules.any { it.payee == "INSURANCE" })
    }

    @Test
    fun `carrying over sets a yearly bill aside as it approaches`() {
        val yearly = ManualRecurringRuleEntity(
            id = "y1", payee = "CAR INSURANCE", direction = "OUT", amountMinor = 36_000, currency = "GBP",
            cadence = "ANNUAL", anchorDay = 25, startDate = "2026-03-25", accountId = "Personal",
        )
        val s = snapshot(salary + cardShop, listOf(yearly), BudgetModel.ROLLOVER)
        val without = snapshot(salary + cardShop, emptyList(), BudgetModel.ROLLOVER)
        // Due next March: by the next payday about seven of its twelve months have gone.
        assertTrue(s.setAsideMinor in 19_000L..23_000L)
        assertEquals(without.availableToSpend - s.setAsideMinor, s.availableToSpend)
    }
}
