package com.spendroid.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the allowance readings a sync's responses carried until the sync finishes and they
 * are saved. The HTTP layer sees the headers; the repository knows when a sync is over.
 */
internal object AllowanceRecorder {
    private val pending = ConcurrentHashMap<String, MutableMap<SyncAllowance.Scope, SyncAllowance.Reading>>()

    fun record(accountId: String, scope: SyncAllowance.Scope, reading: SyncAllowance.Reading) {
        pending.getOrPut(accountId) { ConcurrentHashMap() }[scope] = reading
    }

    fun take(accountId: String): Map<SyncAllowance.Scope, SyncAllowance.Reading> =
        pending.remove(accountId)?.toMap().orEmpty()
}
