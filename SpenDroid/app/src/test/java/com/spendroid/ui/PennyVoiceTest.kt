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

    /** 26 Oct 2026: "before payday on Wednesday", with payday a month off - read as this Wednesday. */
    @Test
    fun `payday is named by its day within the week, and by its date further off`() {
        val monday = java.time.LocalDate.of(2026, 10, 26)
        assertTrue(com.spendroid.domain.PennyVoice.paydayOn(java.time.LocalDate.of(2026, 10, 28), monday) == "on Wednesday")
        assertTrue(com.spendroid.domain.PennyVoice.paydayOn(java.time.LocalDate.of(2026, 11, 25), monday) == "on Wed 25 Nov")
    }

    /** 26 Oct 2026: savings "under usual" on day 4 of 33, their bills not yet out. Not until the end. */
    @Test
    fun `a category isn't under usual until the cycle's nearly over`() {
        val state = Sample.state()
        val early = state.copy(budget = state.budget!!.copy(daysUntilNextIncome = 20))
        val lines = pennyLines(early, early.budget!!.asOf.atTime(8, 5))
        assertTrue(lines.joinToString { it.text }, lines.none { "under usual" in it.text || "quieter" in it.text })
    }
}
