package com.spendroid.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.spendroid.ui.DroidAt
import com.spendroid.ui.DroidFace
import com.spendroid.ui.DroidMood
import com.spendroid.ui.DroidMoment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 5.3.2: every animation, frame by frame - to hold the app's Penny to the mock-ups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1100dp-h1300dp-xhdpi")
class PennyFramesTest {
    @get:Rule val compose = createComposeRule()

    private val green = Color(0xFF3DDC84)
    private val rows = listOf(
        Triple("idle / ahead", DroidFace(DroidMood.AHEAD, 0.42f, 0.55f), DroidMoment.NONE),
        Triple("fill on open", DroidFace(DroidMood.AHEAD, 0.42f, 0.55f), null),
        Triple("tight", DroidFace(DroidMood.TIGHT, 0.62f, 0.5f), DroidMoment.NONE),
        Triple("over", DroidFace(DroidMood.OVER, 0.82f, 0.5f), DroidMoment.NONE),
        Triple("payday", DroidFace(DroidMood.PAYDAY), DroidMoment.NONE),
        Triple("syncing", DroidFace(DroidMood.SYNCING, 0.42f, 0.55f), DroidMoment.NONE),
        Triple("asleep", DroidFace(DroidMood.ASLEEP, 0.42f, 0.55f), DroidMoment.NONE),
        Triple("meet penny", DroidFace(DroidMood.AHEAD, 0.42f, 0.55f), DroidMoment.HELLO),
        Triple("question", DroidFace(DroidMood.AHEAD, 0.42f, 0.55f), DroidMoment.QUESTION),
        Triple("noticed", DroidFace(DroidMood.AHEAD, 0.42f, 0.55f), DroidMoment.NOTICED),
        Triple("noted", DroidFace(DroidMood.AHEAD, 0.42f, 0.55f), DroidMoment.NOTED),
    )
    private val times = listOf(0L, 300L, 600L, 900L, 1_200L, 1_500L, 1_800L, 2_100L, 2_400L, 2_700L)

    @Test
    fun frames() {
        compose.setContent {
            Column(Modifier.background(Color(0xFF17181C)).padding(10.dp)) {
                rows.forEach { (name, face, moment) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(name, color = Color(0xFF9AA0AA), fontSize = 13.sp, modifier = Modifier.padding(top = 30.dp).padding(end = 6.dp).padding(horizontal = 2.dp).run { this })
                        times.forEach { t ->
                            DroidAt(face, 96.dp, green, ms = t, moment = moment ?: DroidMoment.NONE, sinceMoment = t, fillOnOpen = moment == null)
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/penny-frames.png")
    }
}
