package com.spendroid.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.DataMap

/**
 * The phone's daily roundup, kept on the watch until it is read.
 *
 * A notification on its own is a dot, easily missed. This one is also an ongoing activity -
 * the same thing a running timer or workout is - so the watch face shows SpenDroid's icon at
 * its foot until the roundup is opened. Opening it takes the icon away.
 */
data class Roundup(
    val at: Long,
    val headline: String,
    val pace: String,
    val lines: List<String>,
    /** The chime chosen on the phone - WARM, SHAPED or SPARKLE - or empty for none. */
    val chime: String = "",
) {

    companion object {
        const val PATH = "/spendroid/roundup"
        const val EXTRA_OPEN = "com.spendroid.wear.OPEN_ROUNDUP"
        private const val PREFS = "roundup"
        // Silent: the chime, when chosen, is played by the app, and the system's sound on top of it
        // would be two noises for one roundup. A channel's sound cannot be changed once made, so
        // this is a new one, and the old goes.
        private const val CHANNEL_ID = "daily_roundup_quiet"
        private const val OLD_CHANNEL_ID = "daily_roundup"
        private const val NOTIFICATION_ID = 7001

        fun from(map: DataMap): Roundup? {
            val headline = map.getString("headline") ?: return null
            return Roundup(
                at = map.getLong("at"),
                headline = headline,
                pace = map.getString("pace") ?: "ON_TRACK",
                lines = map.getStringArrayList("lines").orEmpty(),
                chime = map.getString("chime").orEmpty(),
            )
        }

        fun load(context: Context): Roundup? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val headline = p.getString("headline", null) ?: return null
            return Roundup(
                at = p.getLong("at", 0L),
                headline = headline,
                pace = p.getString("pace", null) ?: "ON_TRACK",
                lines = p.getString("lines", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }

        /**
         * Keeps a new roundup and puts its icon on the watch face. One already shown - the data
         * layer can hand the same item over again - is left alone rather than buzzing twice.
         */
        fun receive(context: Context, roundup: Roundup) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getLong("at", 0L) >= roundup.at) return
            p.edit()
                .putLong("at", roundup.at)
                .putString("headline", roundup.headline)
                .putString("pace", roundup.pace)
                .putString("lines", roundup.lines.joinToString("\n"))
                .putString("chime", roundup.chime)
                .apply()
            post(context, roundup)
            chime(context, roundup.chime)
        }

        /**
         * Plays the chosen chime once, unless the watch has been told to be quiet - Do Not Disturb,
         * Bedtime, theatre mode. Waits for it to finish: the data layer's service can be stopped as
         * soon as it returns, which would cut the chime short.
         */
        private fun chime(context: Context, name: String) {
            val sound = when (name) {
                "WARM" -> R.raw.chime_warm
                "SHAPED" -> R.raw.chime_shaped
                "SPARKLE" -> R.raw.chime_sparkle
                else -> return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
            val theatre = runCatching {
                android.provider.Settings.Global.getInt(context.contentResolver, "theater_mode_on", 0) == 1
            }.getOrDefault(false)
            if (theatre) return
            runCatching {
                val done = java.util.concurrent.CountDownLatch(1)
                val player = android.media.MediaPlayer.create(
                    context,
                    sound,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    0,
                ) ?: return
                player.setOnCompletionListener { done.countDown() }
                player.start()
                done.await(4, java.util.concurrent.TimeUnit.SECONDS)
                player.release()
            }
        }

        /** Read: the icon leaves the watch face. */
        fun markRead(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        private fun post(context: Context, roundup: Roundup) {
            val manager = NotificationManagerCompat.from(context)
            // Asked for when the watch app is first opened; until allowed, the roundup waits unseen.
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            if (!manager.areNotificationsEnabled()) return
            val system = context.getSystemService(NotificationManager::class.java)
            system.deleteNotificationChannel(OLD_CHANNEL_ID)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Daily roundup", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "The day's roundup, kept on the watch face until you read it"
                    enableVibration(true)
                    setSound(null, null)
                },
            )
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, BudgetActivity::class.java)
                    .putExtra(EXTRA_OPEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_roundup)
                .setContentTitle("SpenDroid roundup")
                .setContentText(roundup.headline)
                .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(roundup.headline) + roundup.lines).joinToString("\n")))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOngoing(true)
                .setContentIntent(open)
            OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_roundup)
                .setTouchIntent(open)
                .setStatus(Status.Builder().addTemplate(roundup.headline).build())
                .build()
                .apply(context)
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }
}
