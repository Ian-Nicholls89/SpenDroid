package com.spendroid.ui

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Penny asleep (0.7.0): on the phone's widgets and the watch's tile from 11pm to 6am - the hours
 * the app's syncs already leave alone. In the apps, opened, she's awake: she's being looked at.
 * Payday's face waits for 6am like any other.
 */
object PennySleep {
    val FROM: LocalTime = LocalTime.of(23, 0)
    val UNTIL: LocalTime = LocalTime.of(6, 0)

    fun asleep(time: LocalTime): Boolean = !time.isBefore(FROM) || time.isBefore(UNTIL)

    /** [face], or asleep if it's night - unless she's busy syncing. */
    fun at(face: DroidFace, time: LocalTime): DroidFace =
        if (asleep(time) && face.mood != DroidMood.SYNCING) face.copy(mood = DroidMood.ASLEEP) else face

    /** When she next falls asleep or wakes: for a redraw then. */
    fun nextChange(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate()
        return listOf(today.atTime(UNTIL), today.atTime(FROM), today.plusDays(1).atTime(UNTIL))
            .first { it.isAfter(now) }
    }

    fun untilNextChange(now: LocalDateTime): Duration = Duration.between(now, nextChange(now))
}
