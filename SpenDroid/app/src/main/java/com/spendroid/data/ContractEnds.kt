package com.spendroid.data

import android.content.Context
import androidx.core.content.edit
import com.spendroid.domain.Contracts
import java.time.LocalDate

/**
 * Contract end dates the user has given, by payee ([Contracts.keyFor]), and the ones whose new
 * price they've said they're happy with - so it stops being pointed out.
 */
class ContractEnds(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("contracts", Context.MODE_PRIVATE)

    fun all(): Map<String, LocalDate> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("end:")) return@mapNotNull null
        runCatching { LocalDate.parse(v as String) }.getOrNull()?.let { k.removePrefix("end:") to it }
    }.toMap()

    fun set(key: String, end: LocalDate?) = prefs.edit {
        if (end == null) remove("end:$key") else putString("end:$key", end.toString())
    }

    fun accepted(): Set<String> = prefs.getStringSet("accepted", emptySet()).orEmpty()

    /** Happy with the price after this end date: no more said about it. */
    fun accept(key: String, end: LocalDate) =
        prefs.edit { putStringSet("accepted", accepted() + Contracts.acceptedKey(key, end)) }
}
