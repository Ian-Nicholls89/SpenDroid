package com.spendroid.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.spendroid.data.remote.InstitutionDto

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "SpenDroid",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "SpenDroid reads your transactions through GoCardless, the open banking service " +
                "your bank already supports. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(20.dp))
        OnboardingStep(
            number = "1",
            title = "Create a free GoCardless account",
            detail = "Sign up at $helpUrl, then open Developers → User secrets and create one. " +
                "There is no charge for personal use.",
        )
        OnboardingStep(
            number = "2",
            title = "Paste the two secrets below",
            detail = "They are stored only on this phone and are never sent anywhere except " +
                "GoCardless itself.",
        )
        OnboardingStep(
            number = "3",
            title = "Approve each bank",
            detail = "Your bank grants access for 90 days at a time. SpenDroid reminds you " +
                "before it lapses, and keeps everything it has already downloaded.",
        )

        Spacer(Modifier.height(20.dp))
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
                "Tap Link next to your bank. The ones below are shown — " +
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
            Box(modifier = Modifier.height(400.dp)) {
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
@Composable
private fun OnboardingStep(number: String, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
