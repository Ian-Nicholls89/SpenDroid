package com.spendroid.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.spendroid.watch.WatchInstaller
import kotlinx.coroutines.launch

/**
 * Settings → Watch: installs the watch app from the phone over the watch's Wireless debugging.
 * The addresses are remembered, so an update after the first time is one tap - once Wireless
 * debugging is switched back on, since the watch turns it off after a while.
 */
@Composable
internal fun WatchSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("watch_install", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var pairAddress by rememberSaveable { mutableStateOf(prefs.getString("pair", "") ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf(prefs.getString("connect", "") ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val paired = remember { mutableStateOf(prefs.getBoolean("paired", false)) }

    Column {
        Text("Watch app", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Installs SpenDroid's complication on your watch. On the watch: Settings → System → About → tap " +
                "Build number 7 times, then Developer options → turn on Wireless debugging. Your phone and " +
                "watch need to be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            if (paired.value) "1. Paired ✓ (pair again if you reset the watch)" else "1. Pair, once",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "On the watch: Wireless debugging → Pair new device. Enter the address and code it shows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = pairAddress,
                onValueChange = { pairAddress = it },
                label = { Text("Pairing address") },
                placeholder = { Text("192.168.1.20:37099") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1.6f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !busy && hostPort(pairAddress) != null && code.length == 6,
            onClick = {
                val (host, port) = hostPort(pairAddress) ?: return@OutlinedButton
                busy = true
                status = "Pairing…"
                scope.launch {
                    val result = WatchInstaller.pair(context, host, port, code)
                    if (result is WatchInstaller.Result.Done) {
                        prefs.edit { putString("pair", pairAddress.trim()).putBoolean("paired", true) }
                        paired.value = true
                        code = ""
                        // The connection address is the pairing host on another port.
                        if (address.isBlank()) address = "$host:"
                    }
                    status = when (result) {
                        WatchInstaller.Result.Done -> "Paired. Now enter the watch's IP address & port below."
                        is WatchInstaller.Result.Failed -> result.message
                    }
                    busy = false
                }
            },
        ) { Text("Pair") }

        Spacer(Modifier.height(16.dp))
        Text("2. Install or update", style = MaterialTheme.typography.labelLarge)
        Text(
            "On the watch's Wireless debugging screen: the IP address & port (not the pairing one).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("IP address & port") },
            placeholder = { Text("192.168.1.20:41555") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy && hostPort(address) != null,
            onClick = {
                val (host, port) = hostPort(address) ?: return@Button
                busy = true
                scope.launch {
                    val result = WatchInstaller.install(context, host, port) { status = it }
                    if (result is WatchInstaller.Result.Done) prefs.edit { putString("connect", address.trim()) }
                    status = when (result) {
                        WatchInstaller.Result.Done ->
                            "Installed. Long-press your watch face → Edit → pick a slot → SpenDroid. " +
                                "You can turn Wireless debugging off again."
                        is WatchInstaller.Result.Failed -> result.message
                    }
                    busy = false
                }
            },
        ) { Text("Install on watch") }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "192.168.1.20:41555" as its host and port, or null until it is one. */
internal fun hostPort(value: String): Pair<String, Int>? {
    val trimmed = value.trim()
    val host = trimmed.substringBeforeLast(':', "").takeIf { it.isNotBlank() } ?: return null
    val port = trimmed.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return host to port
}
