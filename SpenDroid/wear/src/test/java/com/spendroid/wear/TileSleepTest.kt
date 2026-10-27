package com.spendroid.wear

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.7.0 / watch 0.1.14: the tile's Penny asleep overnight; awake, her mood as the figures have it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TileSleepTest {
    private val reading = com.spendroid.wear.app.SampleReading.reading()

    @Test
    fun `asleep at night, her mood by day`() {
        assertEquals(WatchMood.ASLEEP, tileMoodOf(reading, LocalTime.of(23, 30)))
        assertEquals(WatchMood.ASLEEP, tileMoodOf(null, LocalTime.of(4, 0)))
        assertEquals(moodOf(reading), tileMoodOf(reading, LocalTime.of(7, 0)))
    }
}
