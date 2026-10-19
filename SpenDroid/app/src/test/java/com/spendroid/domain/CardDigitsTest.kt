package com.spendroid.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 5.2.2: a card's digits come off a shop's name; a store's number stays. */
class CardDigitsTest {
    private val cards = setOf("4168")

    @Test
    fun `the card's digits go, a store number stays`() {
        assertEquals("WAITROSE STORES", CardDigits.trim("WAITROSE STORES 4168", cards))
        assertEquals("GREGGS", CardDigits.trim("GREGGS  4168 ", cards))
        assertEquals("SPORTSDIRECT 428", CardDigits.trim("SPORTSDIRECT 428", cards))
        assertEquals("TESCO STORES 2231", CardDigits.trim("TESCO STORES 2231", cards))
        // Only at the end, and never the whole name.
        assertEquals("4168 PIZZA", CardDigits.trim("4168 PIZZA", cards))
        assertEquals("4168", CardDigits.trim("4168", cards))
        assertEquals("WAITROSE STORES 4168", CardDigits.trim("WAITROSE STORES 4168", emptySet()))
    }

    @Test
    fun `known digits are four digits from accounts and notifications`() {
        assertEquals(setOf("4168", "0021"), CardDigits.known(listOf("4168", null, "12"), listOf("0021", null, "abcd")))
    }
}
