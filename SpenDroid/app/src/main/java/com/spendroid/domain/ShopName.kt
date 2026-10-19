package com.spendroid.domain

/**
 * A shop's name fit for a sentence - "Penny has a question about your spend at Tesco" - from
 * what a bank or card alert calls it: "TESCO STORES 2231", "SumUp *Pie and Piper", "eBay O*20-
 * 15250-6". Null when there's no clean, short name to be had; the caller says something general.
 */
object ShopName {

    fun clean(raw: String): String? {
        var name = raw.trim()
        // "SumUp *Pie and Piper": a card processor, then the shop. "eBay O*20-...": the shop,
        // then a reference.
        if ('*' in name) {
            val before = name.substringBefore('*').trim()
            val after = name.substringAfter('*').trim()
            name = if (before.lowercase() in PROCESSORS) after else before
        }
        val words = name.split(Regex("\\s+"))
            .filter { w -> w.none(Char::isDigit) && w.length >= 2 && w.lowercase().trim('.', ',') !in FILLER }
        if (words.isEmpty()) return null
        val shouting = words.all { w -> w.none(Char::isLowerCase) }
        val joined = words.joinToString(" ") { w -> if (shouting) w.lowercase().replaceFirstChar(Char::uppercase) else w }
        return joined.takeIf { it.length in 2..24 && it.any(Char::isLetter) }
    }

    private val PROCESSORS = setOf("sumup", "sq", "square", "paypal", "pp", "zettle", "izettle", "iz", "sp", "crv", "stripe")
    private val FILLER = setOf("stores", "store", "ltd", "limited", "plc", "uk", "gb", "superstore", "llp", "inc")
}
