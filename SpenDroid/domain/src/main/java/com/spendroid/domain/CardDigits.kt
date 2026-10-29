package com.spendroid.domain

/**
 * A card's last four digits tacked onto a shop's name: NatWest and Nectar list a card payment
 * pending as "WAITROSE STORES 4168", the 4168 being the card, not the shop. Only digits known
 * to be a card's go - "SPORTSDIRECT 428" keeps its store number.
 */
object CardDigits {

    /** [name] without a trailing card number from [cards]; unchanged if that's all there is. */
    fun trim(name: String, cards: Set<String>): String {
        if (cards.isEmpty()) return name
        val match = TRAILING.find(name) ?: return name
        if (match.groupValues[1] !in cards) return name
        return name.substring(0, match.range.first).trimEnd().ifEmpty { name }
    }

    /** Digits that are a card's: set on an account, or shown in a payment notification. */
    fun known(accountDigits: List<String?>, seenDigits: List<String?>): Set<String> =
        (accountDigits + seenDigits).filterNotNullTo(HashSet()).filterTo(HashSet()) { it.length == 4 && it.all(Char::isDigit) }

    private val TRAILING = Regex("""\s+(\d{4})\s*$""")
}
