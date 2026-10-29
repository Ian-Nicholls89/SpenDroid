package com.spendroid.domain

import com.spendroid.data.db.DuplicateCheckEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bank re-issuing a booked transaction under a new id: the old row stays stored, the new one
 * arrives beside it, and the same money counts twice until the user says otherwise.
 */
class DuplicateCheckTest {

    private val today = LocalDate.of(2026, 9, 29)

    private fun tx(id: String, amountMinor: Long, date: String, payee: String = "COSTA COFFEE", pending: Boolean = false) =
        TransactionEntity(
            accountId = "bank",
            transactionId = id,
            bookingDate = date,
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = pending,
            rawJson = null,
        )

    private val oldest = tx("start", -100, "2026-07-01", "WINDOW START")
    private val shell = tx("shell", -4500, "2026-09-20", "SHELL")
    private val asos = tx("asos", 2999, "2026-09-18", "ASOS REFUND")

    private fun sync(stored: List<TransactionEntity>, fetched: List<TransactionEntity>, checks: List<DuplicateCheckEntity>) =
        DuplicateCheck.afterSync(stored, fetched, checks, today)

    @Test
    fun `a re-issued row is asked about after two syncs and its copy held`() {
        val costa = tx("costa-1", -1249, "2026-09-22", "COSTA COFFEE 1234")
        val copy = tx("costa-9", -1249, "2026-09-23", "COSTA")
        val stored = listOf(oldest, shell, costa)
        val first = sync(stored, listOf(oldest, shell, copy), emptyList())
        assertEquals(DuplicateCheck.State.WATCHING.name, first.single().state)
        assertTrue(DuplicateCheck.heldKeys(first).isEmpty())

        val second = sync(stored + copy, listOf(oldest, shell, copy), first)
        assertEquals(DuplicateCheck.State.ASK.name, second.single().state)
        assertEquals(setOf("bank|costa-9"), DuplicateCheck.heldKeys(second))
        val q = DuplicateCheck.questions(second, stored + copy).single()
        assertEquals("costa-1", q.vanished.transactionId)
        assertEquals("costa-9", q.candidate?.transactionId)
    }

    @Test
    fun `a row missing once and back again is forgotten`() {
        val stored = listOf(oldest, shell)
        val first = sync(stored, listOf(oldest), emptyList())
        assertEquals(1, first.size)
        assertEquals(emptyList<DuplicateCheckEntity>(), sync(stored, stored, first))
    }

    @Test
    fun `a vanished refund with nothing like it asks keep or remove`() {
        val stored = listOf(oldest, asos, shell)
        val fetched = listOf(oldest, shell)
        val checks = sync(stored, fetched, sync(stored, fetched, emptyList()))
        val q = DuplicateCheck.questions(checks, stored).single()
        assertEquals("asos", q.vanished.transactionId)
        assertNull(q.candidate)
        assertTrue(DuplicateCheck.heldKeys(checks).isEmpty())
    }

    @Test
    fun `a row that was already there is never taken for the copy`() {
        // Two identical coffees days apart, both listed all along; one then goes.
        val a = tx("a", -350, "2026-09-10")
        val b = tx("b", -350, "2026-09-12")
        val stored = listOf(oldest, a, b)
        val fetched = listOf(oldest, b)
        val checks = sync(stored, fetched, sync(stored, fetched, emptyList()))
        assertNull(DuplicateCheck.questions(checks, stored).single().candidate)
    }

    @Test
    fun `recent rows, pending rows and rows outside the window are left alone`() {
        val recent = tx("recent", -999, today.minusDays(1).toString())
        val pending = tx("pend", -500, "2026-09-15", pending = true)
        val old = tx("old", -700, "2026-06-01")
        val stored = listOf(oldest, recent, pending, old, shell)
        val fetched = listOf(oldest, shell)
        assertEquals(emptyList<DuplicateCheckEntity>(), sync(stored, fetched, sync(stored, fetched, emptyList())))
    }

    @Test
    fun `a kept answer is remembered`() {
        val stored = listOf(oldest, asos)
        val fetched = listOf(oldest)
        val asked = sync(stored, fetched, sync(stored, fetched, emptyList()))
        val kept = asked.map { it.copy(state = DuplicateCheck.State.KEPT.name) }
        val later = sync(stored, fetched, kept)
        assertEquals(DuplicateCheck.State.KEPT.name, later.single().state)
        assertTrue(DuplicateCheck.questions(later, stored).isEmpty())
    }

    @Test
    fun `two re-issued same-amount rows pair one to one`() {
        val a = tx("a", -350, "2026-09-10")
        val b = tx("b", -350, "2026-09-11")
        val a2 = tx("a2", -350, "2026-09-10")
        val b2 = tx("b2", -350, "2026-09-11")
        val stored = listOf(oldest, a, b)
        val fetched = listOf(oldest, a2, b2)
        val first = sync(stored, fetched, emptyList())
        val second = sync(stored + a2 + b2, fetched, first)
        val pairs = DuplicateCheck.questions(second, stored + a2 + b2).associate { it.vanished.transactionId to it.candidate?.transactionId }
        assertEquals(mapOf("a" to "a2", "b" to "b2"), pairs)
    }

    @Test
    fun `a held copy is not counted in the budget`() {
        val salary = (6..9).map { m -> tx("pay$m", 250000, "2026-%02d-25".format(m), "ACME SALARY") }
        val costa = tx("costa-1", -1249, "2026-09-26")
        val copy = tx("costa-9", -1249, "2026-09-26", "COSTA")
        val all = salary + costa + copy
        val held = setOf("bank|costa-9")
        val counted = all.filterNot { "${it.accountId}|${it.transactionId}" in held }
        val s = BudgetEngine.snapshot(counted, RecurringAnalyzer.analyze(counted), emptyList(), referenceTime = today.atTime(12, 0))
        assertEquals(1249L, s.spentThisCycle)
    }
}
