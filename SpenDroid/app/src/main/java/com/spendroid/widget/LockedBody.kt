package com.spendroid.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.spendroid.MainActivity
import com.spendroid.data.Privacy

/** A widget while SpenDroid is locked and figures are hidden: its heading, "£•••", and the way in. */
@Composable
internal fun LockedBody(title: String, accent: Int) {
    Box(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(WIDGET_CHARCOAL))).cornerRadius(22.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            // Figures hidden, so the droid's visor - the budget used - is hidden too: a plain droid.
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetDroid provides com.spendroid.ui.pennyStill(com.spendroid.ui.DroidFace(), androidx.compose.ui.graphics.Color(accent), 144)) {
                DroidTitle(title.lowercase(), TextStyle(color = ColorProvider(Color(accent)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(Privacy.HIDDEN, style = TextStyle(color = ColorProvider(Color.White), fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Text("Locked · tap to open", style = TextStyle(color = ColorProvider(Color(0xFF9AA0AA)), fontSize = 11.sp))
            Spacer(GlanceModifier.height(8.dp))
            // An empty visor: the shape of the read, none of the figures.
            Image(ImageProvider(visorBitmap(140f, 12f, 0f, null, accent)), contentDescription = null, modifier = GlanceModifier.width(140.dp).height((12f + 2 * VISOR_TICK_OVER).dp))
        }
    }
}
