package com.spendroid.data

import android.content.Context
import androidx.core.content.edit

/**
 * App lock and what shows while locked. The lock is Android's own - fingerprint, face or the
 * phone's PIN - so SpenDroid holds no secret of its own to lose. What shows outside the app -
 * widgets, notifications, the watch - is the user's call, and shows figures unless they say not.
 */
class Privacy(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("privacy", Context.MODE_PRIVATE)

    var lockEnabled: Boolean
        get() = prefs.getBoolean("lock", false)
        set(v) = prefs.edit { putBoolean("lock", v) }

    /** How long after leaving the app it locks again. */
    var lockAfter: LockAfter
        get() = LockAfter.entries.firstOrNull { it.name == prefs.getString("lock_after", null) } ?: LockAfter.MINUTE
        set(v) = prefs.edit { putString("lock_after", v.name) }

    /** A blank card in recent apps, and no screenshots. */
    var hideInRecents: Boolean
        get() = prefs.getBoolean("hide_recents", false)
        set(v) = prefs.edit { putBoolean("hide_recents", v) }

    var widgetFigures: Boolean
        get() = !lockEnabled || prefs.getBoolean("widget_figures", true)
        set(v) = prefs.edit { putBoolean("widget_figures", v) }

    var notificationFigures: Boolean
        get() = !lockEnabled || prefs.getBoolean("notification_figures", true)
        set(v) = prefs.edit { putBoolean("notification_figures", v) }

    var watchFigures: Boolean
        get() = !lockEnabled || prefs.getBoolean("watch_figures", true)
        set(v) = prefs.edit { putBoolean("watch_figures", v) }

    /** The setting as stored, whatever the lock: for the switches themselves. */
    fun stored(key: String): Boolean = prefs.getBoolean(key, true)

    enum class LockAfter(val label: String, val millis: Long) {
        AT_ONCE("Straight away", 0L), MINUTE("1 minute", 60_000L), FIVE("5 minutes", 300_000L), THIRTY("30 minutes", 1_800_000L)
    }

    companion object {
        /** When the app was last left, in this process. */
        @Volatile var leftAt: Long = 0L

        /** Unlocked since the app started; a fresh start - the phone restarted, the app closed - is locked. */
        @Volatile var unlocked: Boolean = false

        /** "£•••": an amount, hidden. */
        const val HIDDEN = "£•••"
    }
}
