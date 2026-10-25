package com.spendroid

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.spendroid.ui.theme.Motion

enum class AppScreen(
    val route: String,
    val title: String,
    /** Bottom-bar label, shorter where the title would not fit under an icon. */
    val shortTitle: String,
    val icon: ImageVector,
) {
    Home("home", "Dashboard", "Home", Icons.Filled.Home),
    Spending("spending", "Spending", "Spending", Icons.AutoMirrored.Filled.ReceiptLong),
    Accounts("accounts", "Accounts", "Accounts", Icons.Filled.AccountBalance),
    Rules("rules", "Regular", "Regular", Icons.Filled.Repeat),
    Settings("settings", "Settings", "Settings", Icons.Filled.Settings);

    companion object {
        fun from(route: String): AppScreen = entries.firstOrNull { it.route == route } ?: Home
    }
}

/**
 * The tabs at the foot, nzb360's way: the chosen one's icon in a pill of the accent, every label
 * shown. Material's bar hid the labels and tinted the pill to its own scheme.
 */
@Composable
internal fun PillNavigationBar(screens: List<AppScreen>, selected: AppScreen, onSelect: (AppScreen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F2025))
            .navigationBarsPadding()
            .height(72.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        screens.forEach { destination ->
            val on = destination == selected
            val pill by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                Motion.change(Motion.SHORT),
                label = "pill",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(destination) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(pill)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        tint = if (on) Color(0xFF111111) else Color(0xFFAAB0BB),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    destination.shortTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) Color.White else Color(0xFFAAB0BB),
                )
            }
        }
    }
}
