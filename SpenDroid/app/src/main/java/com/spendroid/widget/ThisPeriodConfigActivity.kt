package com.spendroid.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.spendroid.BudgetApplication
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.ui.theme.BudgetTheme
import kotlinx.coroutines.launch

/**
 * Choosing what a "This period" widget shows: up to three accounts, in the order tapped, and
 * whether the lines fill up with spending (the default) or drain down with what is left. Opens
 * when the widget is added, and again from long-press → Reconfigure.
 */
class ThisPeriodConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out without choosing does not add the widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val existing = ThisPeriodPrefs.load(this, widgetId)
        val repo = (application as BudgetApplication).repository

        setContent {
            BudgetTheme {
                val scope = rememberCoroutineScope()
                val accounts by produceState(emptyList<AccountEntity>()) { value = repo.accounts() }
                val chosen = remember { mutableStateListOf<String>().apply { existing?.accountIds?.let(::addAll) } }
                var drain by remember { mutableStateOf(existing?.drain ?: false) }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.systemBarsPadding().padding(20.dp)) {
                        Text("This period — which accounts?", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Pick up to ${ThisPeriodPrefs.MAX_ACCOUNTS}, in the order you want them. Tap again to remove.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(accounts, key = { it.id }) { account ->
                                val position = chosen.indexOf(account.id)
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (position >= 0) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        when {
                                            position >= 0 -> chosen.remove(account.id)
                                            chosen.size < ThisPeriodPrefs.MAX_ACCOUNTS -> chosen.add(account.id)
                                        }
                                    },
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(account.label, fontWeight = FontWeight.Bold)
                                            Text(
                                                if (account.accountType == AccountType.CREDIT_CARD) "Statement" else "Pay cycle",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Box(
                                            modifier = Modifier.size(32.dp).clip(CircleShape)
                                                .then(
                                                    if (position >= 0) Modifier.padding(0.dp) else Modifier,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = if (position >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    if (position >= 0) {
                                                        Text("${position + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Lines drain down", fontWeight = FontWeight.Bold)
                                Text(
                                    if (drain) "Showing what's left, and how much of the period is to come"
                                    else "Off: lines fill up with what's been spent",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = drain, onCheckedChange = { drain = it })
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            enabled = chosen.isNotEmpty(),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.align(Alignment.End),
                            onClick = {
                                ThisPeriodPrefs.save(this@ThisPeriodConfigActivity, widgetId, ThisPeriodPrefs.Choice(chosen.toList(), drain))
                                scope.launch {
                                    runCatching {
                                        val glanceId = GlanceAppWidgetManager(this@ThisPeriodConfigActivity).getGlanceIdBy(widgetId)
                                        ThisPeriodWidget().update(this@ThisPeriodConfigActivity, glanceId)
                                    }
                                    setResult(
                                        Activity.RESULT_OK,
                                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                                    )
                                    finish()
                                }
                            },
                        ) { Text("Done") }
                    }
                }
            }
        }
    }
}
