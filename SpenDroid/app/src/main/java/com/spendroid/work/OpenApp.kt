package com.spendroid.work

import android.app.PendingIntent
import android.content.Context

/**
 * What tapping one of the app's notifications does: open the app, as tapping its icon would.
 * Without it a tap only dismissed the notification, which is not what anyone tapping means.
 */
internal fun openAppIntent(context: Context, requestCode: Int, extra: String? = null): PendingIntent? =
    context.packageManager
        .getLaunchIntentForPackage(context.packageName)
        ?.apply { extra?.let { putExtra(it, true) } }
        ?.let {
            PendingIntent.getActivity(
                context,
                requestCode,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

/** Set on the roundup notification's tap, so the app opens on the roundup rather than Home. */
const val EXTRA_OPEN_ROUNDUP = "com.spendroid.OPEN_ROUNDUP"
