package com.spendroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import com.spendroid.web.WebAccess
import com.spendroid.web.WebService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → Computer: "Open on my computer". On, the phone serves a read-only page over the home
 * Wi-Fi; this shows the address to type and the code to pair with, and the computers that were
 * told to remember, each of which can be forgotten.
 */
@Composable
internal fun WebSection() {
    val context = LocalContext.current
    val running by WebAccess.running.collectAsState()
    var computers by remember { mutableStateOf(WebAccess.computers(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    Column {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionHeading("Open on my computer")
                    Text("Your figures on a bigger screen, over your Wi-Fi or your phone's hotspot. Read-only.", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
                }
                Switch(
                    checked = running != null,
                    onCheckedChange = { on ->
                        note = null
                        if (on) {
                            if (!WebService.canServe(context)) note = "Connect to Wi-Fi, or turn on your phone's hotspot and connect the computer to it."
                            else WebService.start(context)
                        } else {
                            WebService.stop(context)
                        }
                    },
                )
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Charcoal.Warn, modifier = Modifier.padding(top = 6.dp)) }
            running?.let { r ->
                Spacer(Modifier.height(14.dp))
                Text("On your computer, type", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
                r.addresses.forEach { place ->
                    // Both, when the phone is on Wi-Fi and sharing a hotspot: whichever the computer is on.
                    if (r.addresses.size > 1) Text(place.label, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 4.dp))
                    Text("http://${place.address}", fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Then this code", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
                Text(r.code.chunked(3).joinToString(" "), fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Works once; a new one appears after. It turns off when you leave Wi-Fi or switch the hotspot off, or after 15 minutes unused.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charcoal.Muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel {
            SectionHeading("Remembered computers", trailing = "30 days each")
            if (computers.isEmpty()) {
                Text("None. Tick \"Remember this computer\" when pairing to skip the code next time.", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp))
            }
            computers.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Last used ${day(c.lastSeen)} · until ${day(c.expires)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Charcoal.Muted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        WebAccess.forget(context, c.hash)
                        computers = WebAccess.computers(context)
                    }) { Text("Forget", color = Charcoal.Bad) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Use it on your home Wi-Fi or your own hotspot: on a shared network others on it could see the traffic.",
            style = MaterialTheme.typography.labelSmall,
            color = Charcoal.Muted,
        )
    }
}

private fun day(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM"))
