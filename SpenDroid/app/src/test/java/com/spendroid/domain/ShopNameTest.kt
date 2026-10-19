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
        assertEquals("Greggs Amesbury", ShopName.clean("GREGGS PLC AMESBURY"))
        assertEquals("The Co-operative", ShopName.clean("THE CO-OPERATIVE"))
        assertEquals("Costa", ShopName.clean("Costa"))
    }

    @Test
    fun `nothing clean to say - none`() {
        assertNull(ShopName.clean("4168"))
        assertNull(ShopName.clean("SALT DELI KITCHEN AND COFFEE HOUSE AMESBURY"))
        assertNull(ShopName.clean("  "))
    }
}
