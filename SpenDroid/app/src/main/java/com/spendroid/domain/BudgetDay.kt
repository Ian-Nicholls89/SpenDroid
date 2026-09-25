package com.spendroid.domain

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Which day the budget is on.
 *
 * The figures are only as new as the last sync, and overnight the bank settles what was
 * pending - a salary most of all. Starting the new day at midnight, before any of that had
 * been fetched, meant payday began with the salary still pending: the cycle could not turn,
 * and the day read as though the money had not come. So the day starts with its first sync,
 * and until then it is still yesterday - which is what yesterday's figures describe.
 *
 * Not indefinitely: a phone offline all morning, or a bank that is down, must not hold the
 * app on yesterday. Four hours after the first sync was due, the day starts regardless.
 */
object BudgetDay {

    private const val GRACE_HOURS = 4L

    /**
     * The moment the budget is worked out at. [lastSync] is when the accounts last synced;
     * [firstSync] is the time the day's first scheduled sync is due.
     */
    fun effective(now: LocalDateTime, lastSync: LocalDateTime?, firstSync: LocalTime): LocalDateTime {
        if (lastSync == null) return now
        if (!lastSync.toLocalDate().isBefore(now.toLocalDate())) return now
        val giveUpAt = now.toLocalDate().atTime(firstSync).plusHours(GRACE_HOURS)
        if (!now.isBefore(giveUpAt)) return now
        return now.toLocalDate().minusDays(1).atTime(LocalTime.MAX)
    }
}
