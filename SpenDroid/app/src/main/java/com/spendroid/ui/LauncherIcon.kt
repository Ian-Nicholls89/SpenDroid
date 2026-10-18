package com.spendroid.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.spendroid.ui.theme.Accent

/**
 * The launcher icon in the app's colour. Each colour has its own launcher entry in the manifest
 * (an activity-alias with that colour's icon); exactly one is switched on at a time. Android has
 * no other way to change an app's icon, and some launchers redraw - or briefly drop - the home
 * screen icon when it changes, so it's the user's choice whether the icon follows.
 */
object LauncherIcon {

    private fun prefs(context: Context) = context.getSharedPreferences("launcher_icon", Context.MODE_PRIVATE)

    /** Whether the launcher icon takes the app's colour. Off: it stays green. */
    fun follows(context: Context): Boolean = prefs(context).getBoolean("follows", false)

    fun setFollows(context: Context, follows: Boolean, accent: Accent) {
        prefs(context).edit { putBoolean("follows", follows) }
        apply(context, if (follows) accent else Accent.GREEN)
    }

    /** Switches the launcher entry to [accent]'s, and every other off. Nothing happens if it's already the one. */
    fun apply(context: Context, accent: Accent) {
        val pm = context.packageManager
        fun component(a: Accent) = ComponentName(context.packageName, "${context.packageName}.${a.alias}")
        val current = Accent.entries.firstOrNull {
            pm.getComponentEnabledSetting(component(it)).let { s ->
                s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it == Accent.GREEN)
            }
        }
        if (current == accent) return
        // The new one first, so there's never a moment with no launcher entry at all.
        pm.setComponentEnabledSetting(component(accent), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        Accent.entries.filter { it != accent }.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
