package com.spendroid.data

import com.spendroid.data.SyncAllowance.Scope
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAllowanceTest {

    private val zone = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0, day: Int = 25) =
        LocalDateTime.of(2026, 9, day, h, m).toInstant(zone).toEpochMilli()

    @Test
    fun `rationed calls are recognised by path`() {
        assertEquals("abc-1" to Scope.BALANCES, SyncAllowance.scopeFor("/api/v2/accounts/abc-1/balances/"))
        assertEquals("abc-1" to Scope.TRANSACTIONS, SyncAllowance.scopeFor("/api/v2/accounts/abc-1/transactions/"))
        assertNull(SyncAllowance.scopeFor("/api/v2/accounts/abc-1/"))
        assertNull(SyncAllowance.scopeFor("/api/v2/requisitions/r-1/"))
    }

    /** GoCardless documents its headers in the HTTP_ form; the dashed form is read too. */
    @Test
    fun `the allowance is read from either spelling of the headers`() {
        val now = at(14)
        val documented = mapOf(
            "HTTP_X_RATELIMIT_ACCOUNT_SUCCESS_LIMIT" to "4",
            "HTTP_X_RATELIMIT_ACCOUNT_SUCCESS_REMAINING" to "2",
            "HTTP_X_RATELIMIT_ACCOUNT_SUCCESS_RESET" to "3600",
        )
        val r = SyncAllowance.fromHeaders({ documented[it] }, now)!!
        assertEquals(SyncAllowance.Reading(4, 2, now + 3_600_000L, now), r)

        val dashed = mapOf("X-RateLimit-Account-Success-Remaining" to "1", "X-RateLimit-Account-Success-Reset" to "60")
        assertEquals(1, SyncAllowance.fromHeaders({ dashed[it] }, now)!!.remaining)
        assertNull(SyncAllowance.fromHeaders({ null }, now))
    }

    @Test
    fun `a refusal says when it will work again`() {
        val body = """{"summary":"Rate limit exceeded","detail":"The rate limit for this resource is 4/day. Please try again in 11700 seconds","status_code":429}"""
        val r = SyncAllowance.fromRefusal(body, at(16, 26))!!
        assertEquals(0, r.remaining)
        assertEquals(4, r.limit)
        assertEquals(at(19, 41), r.resetAt)
    }

    @Test
    fun `a sync has what the scarcer of balances and transactions has`() {
        val now = at(15)
        val readings = mapOf(
            Scope.BALANCES to SyncAllowance.Reading(4, 3, at(20), now),
            Scope.TRANSACTIONS to SyncAllowance.Reading(4, 1, at(19), now),
            Scope.DETAILS to SyncAllowance.Reading(4, 0, at(18), now),
        )
        assertEquals(SyncAllowance.Account(4, 1, at(19)), SyncAllowance.forAccount(readings, now))
    }

    /** Once the reset has passed, the allowance is full again without a sync to say so. */
    @Test
    fun `a spent allowance refills at its reset`() {
        val readings = mapOf(Scope.TRANSACTIONS to SyncAllowance.Reading(4, 0, at(19, 40), at(16)))
        assertTrue(SyncAllowance.exhausted(SyncAllowance.forAccount(readings, at(17)), at(17)))
        val later = SyncAllowance.forAccount(readings, at(19, 41))!!
        assertEquals(4, later.remaining)
        assertFalse(SyncAllowance.exhausted(later, at(19, 41)))
    }

    @Test
    fun `the line under each account`() {
        val now = at(16, 26)
        assertEquals("Sync allowance shows after the next sync", SyncAllowance.summary(null, now, zone))
        assertEquals(
            "2 of 4 syncs left · resets 19:40",
            SyncAllowance.summary(SyncAllowance.Account(4, 2, at(19, 40)), now, zone),
        )
        assertEquals(
            "Limit reached · resets 19:40 (in 3h 14m)",
            SyncAllowance.summary(SyncAllowance.Account(4, 0, at(19, 40)), now, zone),
        )
        assertEquals(
            "Limit reached · resets tomorrow 07:12 (in 14h 46m)",
            SyncAllowance.summary(SyncAllowance.Account(4, 0, at(7, 12, day = 26)), now, zone),
        )
    }

    /** A refused account is tried again just after its reset, if that beats the schedule. */
    @Test
    fun `a catch-up is booked only when it comes before the next scheduled sync`() {
        val now = at(16, 26)
        assertEquals(at(19, 42), SyncAllowance.catchUpAt(listOf(at(19, 40)), nextScheduled = at(20), now = now))
        assertNull(SyncAllowance.catchUpAt(listOf(at(19, 59)), nextScheduled = at(20), now = now))
        assertNull(SyncAllowance.catchUpAt(emptyList(), nextScheduled = at(20), now = now))
    }
}
