package com.spendroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 5.3.1: shop names for Penny's titles - clean, or none at all. */
class ShopNameTest {
    @Test
    fun `the shop, without store numbers, filler or shouting`() {
        assertEquals("Tesco", ShopName.clean("TESCO STORES 2231"))
        assertEquals("Waitrose", ShopName.clean("WAITROSE STORES"))
        assertEquals("Sportsdirect", ShopName.clean("SPORTSDIRECT 428"))
        assertEquals("Pie and Piper", ShopName.clean("SumUp *Pie and Piper"))
        assertEquals("eBay", ShopName.clean("eBay O*20-15250-6"))
        assertEquals("Greggs", ShopName.clean("GREGGS PLC AMESBURY"))
        assertEquals("The Co-operative", ShopName.clean("THE CO-OPERATIVE"))
        assertEquals("Costa", ShopName.clean("Costa"))
    }

    @Test
    fun `nothing clean to say - none`() {
        assertNull(ShopName.clean("4168"))
        assertNull(ShopName.clean("SALT DELI KITCHEN AND COFFEE HOUSE AMESBURY"))
        assertNull(ShopName.clean("  "))
    }

    /** 0.7.1: "Waterstones Salisbury" is Waterstones - the branch is where, not who. */
    @Test
    fun `the branch goes - from the list of towns, or learned from the user's own payees`() {
        assertEquals("Waterstones", ShopName.clean("Waterstones Salisbury"))
        assertEquals("Tesco", ShopName.clean("TESCO STORES 2231 SALISBURY RETAIL PARK"))
        assertEquals("Boots", ShopName.clean("BOOTS MILTON KEYNES"))
        assertEquals("Pret", ShopName.clean("PRET LONDON STATION"))
        // A name that's all town, or would be left a scrap, stays whole.
        assertEquals("Salisbury", ShopName.clean("SALISBURY"))
        assertEquals("Bath", ShopName.clean("Bath"))
        assertEquals("Marks and Bath", ShopName.clean("Marks and Bath"))
        // Learned: a last word after three different shops is a place; a kind of shop isn't.
        val places = ShopName.placesIn(listOf("BOOTS AMPORT", "GREGGS AMPORT", "TESCO AMPORT", "COSTA COFFEE", "NERO COFFEE", "PRET COFFEE"))
        assertEquals(setOf("amport"), places)
        assertEquals("Boots", ShopName.clean("BOOTS AMPORT", places))
        assertEquals("Boots Amport", ShopName.clean("BOOTS AMPORT"))
        assertEquals("Costa Coffee", ShopName.clean("COSTA COFFEE", places))
    }
}
