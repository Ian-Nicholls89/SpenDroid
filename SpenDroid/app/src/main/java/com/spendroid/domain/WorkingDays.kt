package com.spendroid.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which way a payment moves when its nominal date is not a working day.
 *
 * The two directions are genuinely opposite and both are right in their place: wages are
 * paid early so nobody waits over a weekend, while a direct debit is collected late because
 * the bank cannot take it until it is open.
 */
enum class PaymentShift(val label: String, val explanation: String) {
    PREVIOUS_WORKING_DAY(
        "Paid earlier",
        "Moves back to the last working day before it. Usual for wages.",
    ),
    NEXT_WORKING_DAY(
        "Taken later",
        "Moves on to the next working day. Usual for direct debits.",
    ),
    NONE(
        "Exact date",
        "Stays on the date whatever day it falls on.",
    ),
    ;

    companion object {
        fun from(name: String?): PaymentShift? = entries.firstOrNull { it.name == name }

        /** What a payment of this direction does by default. */
        fun defaultFor(direction: Direction): PaymentShift = when (direction) {
            Direction.IN -> PREVIOUS_WORKING_DAY
            Direction.OUT -> NEXT_WORKING_DAY
        }
    }
}

/**
 * Weekends and UK bank holidays, for moving a payment onto a day the banks are open.
 *
 * Holidays come from gov.uk; with none loaded this still handles weekends, which is what the
 * app did before and is right most of the time.
 */
class WorkingDayCalendar(private val holidays: Set<LocalDate> = emptySet()) {

    fun isWorkingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY &&
            date.dayOfWeek != DayOfWeek.SUNDAY &&
            date !in holidays

    /** Steps back until the banks are open. Handles runs, such as Good Friday into Easter. */
    fun previousWorkingDay(date: LocalDate): LocalDate {
        var candidate = date
        var guard = 0
        while (!isWorkingDay(candidate) && guard < MAX_STEPS) {
            candidate = candidate.minusDays(1)
            guard++
        }
        return candidate
    }

    fun nextWorkingDay(date: LocalDate): LocalDate {
        var candidate = date
        var guard = 0
        while (!isWorkingDay(candidate) && guard < MAX_STEPS) {
            candidate = candidate.plusDays(1)
            guard++
        }
        return candidate
    }

    fun adjust(date: LocalDate, shift: PaymentShift): LocalDate = when (shift) {
        PaymentShift.PREVIOUS_WORKING_DAY -> previousWorkingDay(date)
        PaymentShift.NEXT_WORKING_DAY -> nextWorkingDay(date)
        PaymentShift.NONE -> date
    }

    private companion object {
        /** No run of closures is longer than this; stops a bad holiday set looping forever. */
        const val MAX_STEPS = 14
    }
}
