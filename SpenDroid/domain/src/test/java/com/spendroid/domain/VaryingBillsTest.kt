package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monthly payments whose amount changes - an energy bill, a salary with overtime. Grouped by
 * amount to the pound, each month was a group of its own and none was ever seen as regular.
 */
class VaryingBillsTest {

    private val now = LocalDateTime.of(2026, 9, 10, 12, 0)
    private val bank = AccountEntity("bank", "NatWest", "Current", "GBP", 100000, 0L, AccountType.PERSONAL)
    private val card = AccountEntity("card", "Tesco Bank", "Tesco Credit Card", "GBP", -40000, 0L, AccountType.CREDIT_CARD)

    private var seq = 0
    private fun tx(payee: String, amountMinor: Long, date: LocalDate, account: String = "bank", cardPayment: Boolean = false) =
        TransactionEntity(
            accountId = account,
            transactionId = "t${seq++}",
            bookingDate = date.toString(),
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = false,
            rawJson = null,
            isCardPayment = cardPayment,
        )

    private val salary = (5..8).map { m -> tx("ACME LTD SALARY", 250000, LocalDate.of(2026, m, 28)) }
    private val energy = listOf(8412L, 9377L, 7120L, 10255L).mapIndexed { i, pence ->
        tx("OCTOPUS ENERGY", -pence, LocalDate.of(2026, 5 + i, 2))
    }

    private fun snapshot(all: List<TransactionEntity>, accounts: List<AccountEntity> = listOf(bank)) =
        BudgetEngine.snapshot(all, RecurringAnalyzer.analyze(all), accounts, referenceTime = now)

    @Test
    fun `an energy bill that changes each month is a bill`() {
        val rule = RecurringAnalyzer.analyze(energy).single()
        assertTrue(rule.isVariable)
        assertEquals(Cadence.MONTHLY, rule.cadence)
        // The middle of the latest three: 93.77, 71.20, 102.55.
        assertEquals(-9377L, rule.amountMinor)
    }

    @Test
    fun `it is budgeted for and kept out of spending`() {
        val s = snapshot(salary + energy)
        assertEquals(9377L, s.fixedMonthlyOutgoings)
        assertEquals(0L, s.spentThisCycle)
    }

    @Test
    fun `a weekly shop at one supermarket is not a bill`() {
        val shop = (0 until 12).map { w -> tx("TESCO STORES", -(4000L + w * 350), LocalDate.of(2026, 6, 1).plusWeeks(w.toLong())) }
        assertEquals(emptyList<RecurringRule>(), RecurringAnalyzer.analyze(shop))
    }

    @Test
    fun `wildly different amounts are not a bill`() {
        val odd = listOf(1500L, 9000L, 2500L, 30000L).mapIndexed { i, p -> tx("AMAZON", -p, LocalDate.of(2026, 5 + i, 10)) }
        assertEquals(emptyList<RecurringRule>(), RecurringAnalyzer.analyze(odd))
    }

    @Test
    fun `a salary with overtime still sets the pay cycle`() {
        val pay = listOf(241000L, 262500L, 255020L, 248800L).mapIndexed { i, p ->
            tx("ACME LTD SALARY", p, LocalDate.of(2026, 5 + i, 28))
        }
        val s = snapshot(pay)
        assertEquals(LocalDate.of(2026, 8, 28), s.cycleStart)
        assertTrue(s.incomeRules.single().isVariable)
    }

    @Test
    fun `paying a card bill that varies is not counted twice`() {
        val bills = listOf(21040L, 18833L, 25512L, 19999L)
        val payments = bills.flatMapIndexed { i, p ->
            val date = LocalDate.of(2026, 5 + i, 15)
            listOf(
                tx("TESCO BANK CREDIT CARD", -p, date),
                tx("PAYMENT RECEIVED THANK YOU", p, date, account = "card", cardPayment = true),
            )
        }
        val s = snapshot(salary + payments, listOf(bank, card))
        assertFalse(s.fixedRules.any { it.payee.contains("TESCO", true) })
        assertEquals(0L, s.fixedMonthlyOutgoings)
    }
}
