package com.spendroid.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5.5.2: how far beyond her box each mood and moment draws, frame by frame - above her within
 * [PENNY_REACH], the room left wherever she sits at the top of something that clips. (Meet
 * Penny's drop-in starts higher on purpose.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PennyReachTest {
    @Test
    fun `nothing reaches further above her than the room left for it`() {
        val out = StringBuilder()
        val cases = DroidMood.entries.map { it to DroidMoment.NONE } + DroidMoment.entries.filter { it != DroidMoment.NONE }.map { DroidMood.AHEAD to it } +
            listOf(DroidMood.AHEAD to null)
        for ((mood, moment) in cases) {
            var top = 0; var left = 0; var right = 0; var bottom = 0
            for (ms in 0L..8_000L step 80) {
                val img = androidx.compose.ui.graphics.ImageBitmap(324, 324)
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                    androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(img), Size(324f, 324f),
                ) {
                    translate(108f, 108f) {
                        val face = DroidFace(mood, 0.5f, 0.5f)
                        val pose = poseAt(mood, moment ?: DroidMoment.NONE, ms, ms, ms, moment == null)
                        drawPose(face, pose, droidColour(mood, Color(0xFF3DDC84)), Color.White)
                    }
                }
                val bmp = img.asAndroidBitmap()
                val px = IntArray(324 * 324).also { bmp.getPixels(it, 0, 324, 0, 0, 324, 324) }
                for (y in 0 until 324) for (x in 0 until 324) {
                    if (android.graphics.Color.alpha(px[y * 324 + x]) > 40) {
                        top = maxOf(top, 108 - y); left = maxOf(left, 108 - x)
                        right = maxOf(right, x - 215); bottom = maxOf(bottom, y - 215)
                    }
                }
            }
            out.append("$mood ${moment ?: "FILL"}: top $top left $left right $right bottom $bottom\n")
            if (moment != DroidMoment.HELLO) {
                org.junit.Assert.assertTrue("$mood ${moment ?: "fill"} reaches $top above her", top <= PENNY_REACH * 108)
            }
        }
    }
}
