package com.spendroid.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.spendroid.R
import com.spendroid.ui.theme.Charcoal

/**
 * The roundup's chime on the watch: off, or one of three, each the same five notes - G C E D C -
 * played a different way. The watch plays it; the phone keeps the choice and sends it with each
 * roundup, so there is nothing to set on the watch.
 */
enum class Chime(val label: String, val sound: Int) {
    WARM("Warm", R.raw.chime_warm),
    SHAPED("Shaped", R.raw.chime_shaped),
    SPARKLE("Sparkle", R.raw.chime_sparkle),
    ;

    companion object {
        private const val PREFS = "watch_chime"
        private const val KEY = "chime"

        /** The chosen chime, or null when it is off - which it is until chosen. */
        fun chosen(context: Context): Chime? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { name -> entries.firstOrNull { it.name == name } }

        fun choose(context: Context, chime: Chime?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, chime?.name) }
        }

        /** Plays [chime] on the phone, to hear it before choosing. */
        fun preview(context: Context, chime: Chime) {
            runCatching {
                MediaPlayer.create(
                    context,
                    chime.sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    0,
                )?.apply {
                    setOnCompletionListener { it.release() }
                    start()
                }
            }
        }
    }
}

/** Settings → Watch: whether the roundup chimes on the watch, and which chime. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChimeSetting() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(Chime.chosen(context)) }
    Panel {
        SectionHeading("Chime with the roundup", trailing = "on the watch")
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColourPill(
                "Off",
                if (chosen == null) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh,
                {
                    chosen = null
                    Chime.choose(context, null)
                },
                textColour = if (chosen == null) Color(0xFF111111) else Color.White,
            )
            Chime.entries.forEach { chime ->
                val on = chosen == chime
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColourPill(
                        chime.label,
                        if (on) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh,
                        {
                            chosen = chime
                            Chime.choose(context, chime)
                            Chime.preview(context, chime)
                        },
                        textColour = if (on) Color(0xFF111111) else Color.White,
                    )
                    IconButton(onClick = { Chime.preview(context, chime) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${chime.label}", tint = Charcoal.Muted)
                    }
                }
            }
        }
        Text(
            "The same five notes, three ways. Quieter and thinner on the watch's small speaker. " +
                "Silent in Do Not Disturb, Bedtime and theatre mode.",
            style = MaterialTheme.typography.labelSmall,
            color = Charcoal.Muted,
        )
    }
}
