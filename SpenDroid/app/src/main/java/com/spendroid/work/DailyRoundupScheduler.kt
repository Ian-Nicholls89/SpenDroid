package com.spendroid.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.spendroid.BudgetApplication
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs the daily jobs at the time the user chose, every day.
 *
 * They used to be repeating jobs, which WorkManager treats as "once in each 24 hours" rather
 * than "at 21:00": with no window given it may run one anywhere in the day, and each period is
 * timed from when the last one actually ran, so a late run pushed every later one too. The
 * roundup set for 21:00 arrived at five in the afternoon. Now each run is a single job booked
 * for the next occurrence of the chosen time, and books the one after as it finishes - so
 * the clock, not the last run, sets the time, day after day and across the clock change.
 */
object DailyRoundupScheduler {

    /** The jobs that follow the user's notification time, and how far ahead of it each runs. */
    enum class Daily(val tag: String, val minutesBefore: Long, val needsNetwork: Boolean) {
        /** An hour ahead, so the roundup reports fresh figures. */
        SYNC("daily_sync_at", 60, needsNetwork = true),
        ROUNDUP("daily_roundup_at", 0, needsNetwork = false),
        ALERTS("daily_alerts_at", 0, needsNetwork = false),
        REAUTH("reauth_reminders_at", 0, needsNetwork = false),
    }

    /** The old repeating jobs, cancelled so they cannot fire alongside the new ones. */
    private val RETIRED = listOf("daily_sync", "daily_roundup", "daily_alerts", "reauth_reminders")

    private const val UPDATE_CHECK_UNIQUE_NAME = "update_checker"
    private const val DEFAULT_TIME = "21:00"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Books every daily job. On app start [reschedule] is false and anything already booked is
     * left alone; when the user changes the time it is true, and the bookings are redone.
     */
    fun schedule(context: Context, reschedule: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch {
            val manager = WorkManager.getInstance(appContext)
            RETIRED.forEach { manager.cancelUniqueWork(it) }

            val time = notificationTime(appContext)
            Daily.entries.forEach { job ->
                if (reschedule) {
                    manager.cancelAllWorkByTag(job.tag)
                } else if (isBooked(manager, job)) {
                    return@forEach
                }
                manager.enqueue(request(job, time))
            }

            // The update check is weekly and its hour does not matter, so it stays repeating.
            val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckerWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(UPDATE_CHECK_UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, updateRequest)
        }
    }

    /**
     * Books a job's next run. Called by the job itself as it finishes: from inside a running
     * job, replacing it by name would cancel the job doing the replacing, so the next run is a
     * fresh booking under the job's tag instead.
     */
    suspend fun bookNext(context: Context, job: Daily) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).enqueue(request(job, notificationTime(appContext)))
    }

    private suspend fun isBooked(manager: WorkManager, job: Daily): Boolean =
        manager.getWorkInfosByTagFlow(job.tag).first().any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }

    private suspend fun notificationTime(context: Context): LocalTime {
        val app = context as? BudgetApplication
        val stored = app?.repository?.notificationTime?.first() ?: DEFAULT_TIME
        return runCatching { LocalTime.parse(stored, DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrDefault(LocalTime.of(21, 0))
    }

    private fun request(job: Daily, time: LocalTime): OneTimeWorkRequest {
        val delay = delayUntilNext(ZonedDateTime.now(), time.minusMinutes(job.minutesBefore))
        val builder = when (job) {
            Daily.SYNC -> OneTimeWorkRequest.Builder(DailySyncWorker::class.java)
            Daily.ROUNDUP -> OneTimeWorkRequest.Builder(DailyRoundupWorker::class.java)
            Daily.ALERTS -> OneTimeWorkRequest.Builder(AlertsWorker::class.java)
            Daily.REAUTH -> OneTimeWorkRequest.Builder(ReauthNotificationWorker::class.java)
        }
        return builder
            .addTag(job.tag)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .apply {
                // Without the network an offline run burns the day's sync, and anything that
                // ages past the API's 90-day window cannot be fetched again.
                if (job.needsNetwork) {
                    setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                }
            }
            .build()
    }

    /**
     * How long until [at] next comes round. A time already passed today means tomorrow, so a
     * job finishing a few seconds after its own time books the following day rather than now.
     * Worked in zoned time, so the day the clocks change is 23 or 25 hours long, as it is.
     */
    internal fun delayUntilNext(now: ZonedDateTime, at: LocalTime, zone: ZoneId = now.zone): Duration {
        var next = now.toLocalDate().atTime(at).atZone(zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(at).atZone(zone)
        return Duration.between(now, next)
    }
}

/**
 * Runs a daily job's day, then books the next one. A day that fails still books tomorrow: a
 * thrown error used to be the end of it until the app was next opened. A retry is the same
 * day trying again, so it books nothing.
 */
internal suspend fun CoroutineWorker.daily(
    job: DailyRoundupScheduler.Daily,
    body: suspend () -> ListenableWorker.Result,
): ListenableWorker.Result {
    val result = try {
        body()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ListenableWorker.Result.failure()
    }
    // Compared by value: the Retry class itself is internal to WorkManager, but every retry
    // result equals every other.
    if (result != ListenableWorker.Result.retry()) {
        runCatching { DailyRoundupScheduler.bookNext(applicationContext, job) }
    }
    return result
}
