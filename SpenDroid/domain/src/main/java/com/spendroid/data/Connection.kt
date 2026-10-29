package com.spendroid.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A bank linked through GoCardless: which, its accounts, and when access was granted. */
data class Connection(
    val institutionId: String,
    val institutionName: String,
    val requisitionId: String,
    val accountIds: List<String>,
    /** When access was granted, in epoch millis. Zero when it is not known yet. */
    val createdAt: Long = System.currentTimeMillis(),
) {
    /**
     * Days until the bank's consent runs out, negative once it has. Null when the grant date
     * is unknown - a guess here either nags for nothing or stays quiet until syncing stops.
     */
    fun daysUntilExpiry(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (createdAt <= 0L) return null
        val granted = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, granted.plusDays(ACCESS_DAYS)).toInt()
    }

    companion object {

        /** How long a bank's consent lasts under PSD2, and what GoCardless grants by default. */
        const val ACCESS_DAYS = 90L
    }
}
