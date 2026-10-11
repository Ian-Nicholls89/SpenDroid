package com.spendroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A pending row has no booking date, and often no id. Made up without a date, every £11.35 lunch
 * at one place had the same id, and a card alert matched to last week's lunch was taken to have
 * claimed this week's - so this week's stayed counted twice.
 */
class PendingIdTest {

    @Test
    fun `pending purchases on different days get different ids`() {
        val lastWeek = GoCardlessRepository.madeUpId(null, "2026-10-03", -1_135, "FOUR ACRES SF CONNECT 4168", mutableMapOf())
        val thisWeek = GoCardlessRepository.madeUpId(null, "2026-10-10", -1_135, "FOUR ACRES SF CONNECT 4168", mutableMapOf())
        assertNotEquals(lastWeek, thisWeek)
    }

    @Test
    fun `two on the same day are told apart, and a booked row's id is as it always was`() {
        val seen = mutableMapOf<String, Int>()
        val first = GoCardlessRepository.madeUpId(null, "2026-10-10", -1_135, "FOUR ACRES", seen)
        val second = GoCardlessRepository.madeUpId(null, "2026-10-10", -1_135, "FOUR ACRES", seen)
        assertNotEquals(first, second)
        assertEquals(
            GoCardlessRepository.fallbackTransactionId("2026-10-10", -1_135, "FOUR ACRES", 1),
            GoCardlessRepository.madeUpId("2026-10-10", "2026-10-09", -1_135, "FOUR ACRES", mutableMapOf()),
        )
    }
}
