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
import androidx.work.workDataOf
import com.spendroid.BudgetApplication
import com.spendroid.data.SyncAllowance
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

    /**
     * The daily jobs. The three syncs take their times from [syncTimes]; the rest run at the
     * notification time itself.
     */
    enum class Daily(val tag: String, val syncSlot: Int?, val needsNetwork: Boolean) {
        SYNC_1("daily_sync_1_at", 0, needsNetwork = true),
        SYNC_2("daily_sync_2_at", 1, needsNetwork = true),
        SYNC_3("daily_sync_3_at", 2, needsNetwork = true),
        ROUNDUP("daily_roundup_at", null, needsNetwork = false),
        ALERTS("daily_alerts_at", null, needsNetwork = false),
        REAUTH("reauth_reminders_at", null, needsNetwork = false),
        ;

        fun at(notification: LocalTime): LocalTime =
            syncSlot?.let { syncTimes(notification)[it] } ?: notification
    }

    /** The old repeating jobs, cancelled so they cannot fire alongside the new ones. */
    private val RETIRED = listOf("daily_sync", "daily_roundup", "daily_alerts", "reauth_reminders")

    /** The single evening sync these three replaced. */
    private const val RETIRED_SYNC_TAG = "daily_sync_at"

    /** Input key telling a sync which of the three it is, so it books its own next run. */
    const val JOB_KEY = "daily_job"

    /** Input key marking a one-off sync booked for just after a bank's allowance resets. */
    const val CATCH_UP_KEY = "catch_up"
    private const val CATCH_UP_NAME = "sync_catch_up"

    /**
     * Books a one-off sync just after the earliest allowance reset among refused accounts, when
     * that beats the next scheduled slot. Replaces any earlier catch-up, so there is only ever
     * one, at the time that matters now.
     */
    suspend fun bookCatchUp(context: Context, fromCatchUp: Boolean = false) {
        val app = context.applicationContext as? BudgetApplication ?: return
        val repo = app.repository
        val now = System.currentTimeMillis()
        val resets = repo.accounts().mapNotNull { account ->
            repo.allowanceFor(account.id, now)?.takeIf { SyncAllowance.exhausted(it, now) }?.resetAt
        }
        val zoned = ZonedDateTime.now()
        val nextSlot = syncTimes(notificationTime(context.applicationContext))
            .minOf { zoned.plus(delayUntilNext(zoned, it)).toInstant().toEpochMilli() }
        val at = SyncAllowance.catchUpAt(resets, nextSlot, now) ?: return
        val request = OneTimeWorkRequest.Builder(DailySyncWorker::class.java)
            .setInputData(workDataOf(CATCH_UP_KEY to true))
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            // From inside a running catch-up, replacing by name would cancel the job doing the
            // booking; appending queues the next one behind it instead.
            .enqueueUniqueWork(
                CATCH_UP_NAME,
                if (fromCatchUp) androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE else androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    private val DAY_SLOTS = listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0))
    private val QUIET_FROM: LocalTime = LocalTime.of(23, 0)
    private val QUIET_UNTIL: LocalTime = LocalTime.of(6, 0)

    /**
     * When the three daily bank syncs run: morning, afternoon and evening, never overnight, when
     * little banking happens - leaving one of the bank's four daily calls for a manual refresh.
     *
     * The slot nearest an hour before the roundup moves to exactly that, so the roundup reports
     * fresh figures whatever time it is set for. The slots are six hours apart and the move is
     * at most three, so no two syncs are ever closer than three hours. An hour before a
     * small-hours roundup would be the middle of the night, and then nothing moves.
     */
    internal fun syncTimes(notification: LocalTime): List<LocalTime> {
        val slots = DAY_SLOTS.toMutableList()
        val pre = notification.minusHours(1)
        val overnight = !pre.isBefore(QUIET_FROM) || pre.isBefore(QUIET_UNTIL)
        if (!overnight) {
            fun distance(t: LocalTime): Int {
                val d = Math.floorMod(t.toSecondOfDay() - pre.toSecondOfDay(), 86_400)
                return minOf(d, 86_400 - d)
            }
            // A tie goes to the earlier slot, which then moves later rather than earlier.
            val nearest = slots.indices.minWith(compareBy({ distance(slots[it]) }, { it }))
            slots[nearest] = pre
        }
        return slots.sorted()
    }

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
            manager.cancelAllWorkByTag(RETIRED_SYNC_TAG)

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
        val delay = delayUntilNext(ZonedDateTime.now(), job.at(time))
        val builder = when (job) {
            Daily.SYNC_1, Daily.SYNC_2, Daily.SYNC_3 -> OneTimeWorkRequest.Builder(DailySyncWorker::class.java)
                .setInputData(workDataOf(JOB_KEY to job.name))
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
