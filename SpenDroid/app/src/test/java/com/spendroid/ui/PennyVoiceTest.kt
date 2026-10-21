package com.spendroid.ui

import com.spendroid.screenshots.Sample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.6: Penny's lines on Home - every one with real figures, nothing half-said, and several to choose from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PennyVoiceTest {

    @Test
    fun `morning, afternoon and evening she has several things to say, all of them whole`() {
        val state = Sample.state()
        val out = StringBuilder()
        for (hour in listOf(8, 13, 20)) {
            val lines = pennyLines(state, state.budget!!.asOf.atTime(hour, 5))
            out.append("--- $hour:05\n")
            lines.forEach { out.append("${it.rank} ${it.kind}: ${it.text}\n") }
            assertTrue("$hour: ${lines.size} lines", lines.size >= 3)
            lines.forEach { l ->
                assertFalse(l.text, Regex("null|NaN|£-|-£|\\$\\{| {2}|\\.\\.").containsMatchIn(l.text))
                assertTrue(l.text, l.text.endsWith(".") || l.text.endsWith("!"))
            }
            assertTrue("ranked", lines.zipWithNext().all { (a, b) -> a.rank >= b.rank })
        }
        java.io.File("build/penny-voice.txt").writeText(out.toString())
    }
}
