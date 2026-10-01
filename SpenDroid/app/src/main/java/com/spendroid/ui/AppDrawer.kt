package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.data.SyncAllowance
import com.spendroid.data.db.AccountEntity
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where the side menu can take you. */
enum class DrawerTarget { OVERVIEW, REGULAR, INSIGHTS, SETTINGS }

/**
 * The side menu, as nzb360 lists its services: a diagonal banner in the accent with a badge for
 * how the bank's allowance stands, each account a "service" in its own colour with its balance,
 * then the tools.
 */
@Composable
fun AppDrawer(
    state: RootUiState,
    current: DrawerTarget?,
    onTarget: (DrawerTarget) -> Unit,
    onAccount: (AccountEntity) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(Color(0xFF1F2025))
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .drawDiagonal(accent),
        ) {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 40.dp)) {
                Text("SPENDROID", fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 3.sp, color = Color.White)
                val updated = state.accounts.maxOfOrNull { it.lastSynced }?.takeIf { it > 0L }
                Text(
                    "${state.accounts.size} account${if (state.accounts.size == 1) "" else "s"}" +
                        (updated?.let { " · updated ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            // nzb360's PRO badge, here the allowance at its tightest across the accounts.
            val now = System.currentTimeMillis()
            val worst = state.accounts.map { SyncAllowance.status(state.syncAllowances[it.id], now) }.maxByOrNull { it.ordinal }
            val (badge, colour) = when (worst) {
                SyncAllowance.Status.UNAVAILABLE -> "NO SYNCS LEFT" to Charcoal.Bad
                SyncAllowance.Status.LAST_ONE -> "1 SYNC LEFT" to Charcoal.Warn
                else -> "SYNC AVAILABLE" to Charcoal.In
            }
            Text(
                badge,
                fontFamily = Lato,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                color = colour,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .background(Color(0xFF111111))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        DrawerItem("Overview", Icons.Filled.Dashboard, current == DrawerTarget.OVERVIEW) { onTarget(DrawerTarget.OVERVIEW) }
        SectionHeading("Accounts", Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp), colour = Charcoal.Muted)
        state.accounts.forEach { account ->
            val c = colourOf(account, state.accounts)
            val owed = state.budget?.cardBills?.firstOrNull { it.cardAccountId == account.id }?.outstandingMinor
            val balance = owed?.let { -it } ?: account.balanceMinor
            Row(
                Modifier.fillMaxWidth().clickable { onAccount(account) }.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(c), contentAlignment = Alignment.Center) {
                    Monogram(account.institutionName.ifBlank { account.label }, 26.dp)
                }
                Spacer(Modifier.width(16.dp))
                Text(account.label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                balance?.let { Text(poundsShort(it), style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFD2D8)) }
            }
        }
        SectionHeading("Tools", Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp), colour = Charcoal.Muted)
        DrawerItem("Regular payments", Icons.Filled.Repeat, current == DrawerTarget.REGULAR) { onTarget(DrawerTarget.REGULAR) }
        DrawerItem("Insights", Icons.Filled.Insights, current == DrawerTarget.INSIGHTS) { onTarget(DrawerTarget.INSIGHTS) }
        Box(Modifier.padding(horizontal = 22.dp, vertical = 10.dp).fillMaxWidth().height(1.dp).background(Charcoal.Line))
        DrawerItem("Settings", Icons.Filled.Settings, current == DrawerTarget.SETTINGS, muted = true) { onTarget(DrawerTarget.SETTINGS) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Charcoal.PanelHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF3A3D45)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (muted) Charcoal.Muted else Color.White)
    }
}

/** "£1,743", "−£139": whole pounds, for a balance beside a name. */
private fun poundsShort(minor: Long): String {
    val pounds = kotlin.math.abs(minor) / 100
    return (if (minor < 0) "−" else "") + "£" + "%,d".format(pounds)
}

/** nzb360's diagonal banner: the accent on the left, cut at a slant into the menu's grey. */
private fun Modifier.drawDiagonal(colour: Color): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(Color(0xFF1F2025))
        val cut = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width * 0.62f, 0f)
            lineTo(size.width * 0.38f, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(cut, androidx.compose.ui.graphics.Brush.linearGradient(listOf(colour, colour.darken(0.45f)), Offset.Zero, Offset(size.width, size.height)))
    },
)

