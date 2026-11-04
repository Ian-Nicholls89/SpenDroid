package com.spendroid.wear

/**
 * What the segmented arc shows, worked out apart from Android so it can be tested on its own.
 *
 * Since watch 0.1.16 it is Home's bar, filling with what's spent: the account's spending, then the
 * cards' (with cards, the month's), then what is left, dark. The amber band for behind pace is
 * gone - the plain arc and the watch app still show the pace.
 */
object BudgetArc {

    enum class Kind { ACCOUNT, CARDS, LEFT }

    data class Segment(val kind: Kind, val weight: Float)

    /** Shares under this are too thin to draw and are left out. */
    private const val MIN_WEIGHT = 0.005f

    /**
     * [account] and [cards] are each a share of the budget spent; with no cards, [cards] is zero
     * and [account] all that's been used.
     */
    fun segments(account: Float, cards: Float): List<Segment> {
        val a = account.coerceIn(0f, 1f)
        val c = cards.coerceIn(0f, 1f - a)
        val left = (1f - a - c).coerceAtLeast(0f)
        return listOf(
            Segment(Kind.ACCOUNT, a),
            Segment(Kind.CARDS, c),
            Segment(Kind.LEFT, left),
        ).filter { it.weight >= MIN_WEIGHT }
            // Nothing at all to draw still needs an arc.
            .ifEmpty { listOf(Segment(Kind.LEFT, 1f)) }
    }

    /**
     * What's sent to the face: always all three, the account's, the cards', then what is left,
     * any empty one a hairline. Some faces - the user's Pixel face among them - ignore the colours
     * given and paint by position (light blue, red, green): in this order what's left is still the
     * green one, and with three always there it never moves.
     */
    fun forFace(segments: List<Segment>): List<Segment> {
        val weights = segments.associate { it.kind to it.weight }
        return Kind.entries.map { Segment(it, weights[it]?.takeIf { w -> w > 0f } ?: HAIRLINE) }
    }

    /** Too thin to see, but there: it holds its place so the next one gets the right colour. */
    private const val HAIRLINE = 0.001f

    /** The colour for the single-colour arc faces without segments fall back to. */
    enum class Pace { ON_TRACK, TIGHT, OVER }

    fun pace(name: String?): Pace = Pace.entries.firstOrNull { it.name == name } ?: Pace.ON_TRACK
}
