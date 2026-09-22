package com.spendroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.spendroid.data.remote.InstitutionDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: RootUiState,
    onSaveSecret: (String, String) -> Unit,
    onSearch: (String) -> Unit,
    onLink: (InstitutionDto) -> Unit,
) {
    var secretId by rememberSaveable { mutableStateOf("") }
    var secretKey by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val helpUrl = "https://ob.nordigen.com"

    Scaffold(
        topBar = { TopAppBar(title = { Text("Connect your banks") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                "Enter your GoCardless Bank Account Data user secret once, then link each bank.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = secretId,
                onValueChange = { secretId = it },
                label = { Text("secret_id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secretKey,
                onValueChange = { secretKey = it },
                label = { Text("secret_key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSaveSecret(secretId, secretKey) },
                enabled = secretId.isNotBlank() && secretKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save secret")
            }

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!state.hasCredentials) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Get your secret from $helpUrl (Developer > User secrets). " +
                        "It is stored only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Tap Link next to your bank. The three below are shown first — " +
                        "if you see several entries, pick the personal account one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        onSearch(q)
                    },
                    label = { Text("Search banks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                state.linkingBank?.let { bank ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Waiting for you to authorise $bank in your banking app…")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.institutions, key = { it.id }) { institution ->
                            InstitutionRow(
                                institution = institution,
                                enabled = state.linkingBank == null,
                                onLink = { onLink(institution) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionRow(
    institution: InstitutionDto,
    enabled: Boolean,
    onLink: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(institution.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    institution.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onLink, enabled = enabled) {
                Text("Link")
            }
        }
    }
}