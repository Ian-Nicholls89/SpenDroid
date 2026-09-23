package com.spendroid.data

import com.spendroid.data.db.BankHolidayEntity
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The UK bank holiday calendar, from gov.uk.
 *
 * The published file carries years of past dates and only a finite run of future ones - it
 * currently ends a couple of years out. Past dates are discarded on the way in, and the
 * calendar is refetched once the stored future dates are nearly used up rather than on a
 * timer, so it is fetched when it is actually needed and not otherwise.
 */
object BankHolidays {

    const val DEFAULT_DIVISION = "england-and-wales"

    /** Refetch once this few future dates remain, so the calendar never runs dry unnoticed. */
    private const val REFRESH_WHEN_REMAINING = 1

    private const val URL = "https://www.gov.uk/bank-holidays.json"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun needsRefresh(remainingFutureDates: Int): Boolean =
        remainingFutureDates <= REFRESH_WHEN_REMAINING

    /** Future holidays for every division, so a move elsewhere needs no second fetch. */
    suspend fun fetch(today: LocalDate = LocalDate.now()): List<BankHolidayEntity> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(URL)
                .header("User-Agent", "SpenDroid")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                parse(body, today)
            }
        }

    internal fun parse(json: String, today: LocalDate): List<BankHolidayEntity> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<BankHolidayEntity>()
        root.keys().forEach { division ->
            val events = root.optJSONObject(division)?.optJSONArray("events") ?: return@forEach
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val date = event.optString("date").takeIf { it.isNotBlank() } ?: continue
                val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
                // Past dates are only weight; the calendar is used to look forwards.
                if (parsed.isBefore(today)) continue
                out += BankHolidayEntity(
                    date = date,
                    division = division,
                    title = event.optString("title"),
                )
            }
        }
        return out
    }
}
