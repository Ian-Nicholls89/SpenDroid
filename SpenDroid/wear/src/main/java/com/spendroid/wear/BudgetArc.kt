package com.spendroid.wear

/**
 * What the arc shows, worked out apart from Android so it can be tested on its own.
 *
 * The arc reads the way the widget's bar does: green is the share of the budget left, and when
 * that is less than the share of the cycle left - spending running ahead of the days - an amber
 * band covers the gap, ending where the widget's pace tick would sit. Ahead of pace there is no
 * band. The rest of the arc is the part already spent.
 */
object BudgetArc {

    enum class Kind { LEFT, BEHIND, SPENT }

    data class Segment(val kind: Kind, val weight: Float)

    /** Shares under this are too thin to draw and are left out. */
    private const val MIN_WEIGHT = 0.005f

    fun segments(budgetLeft: Float, cycleLeft: Float?): List<Segment> {
        val left = budgetLeft.coerceIn(0f, 1f)
        val behind = cycleLeft?.let { (it.coerceIn(0f, 1f) - left).coerceAtLeast(0f) } ?: 0f
        val spent = (1f - left - behind).coerceAtLeast(0f)
        return listOf(
            Segment(Kind.LEFT, left),
            Segment(Kind.BEHIND, behind),
            Segment(Kind.SPENT, spent),
        ).filter { it.weight >= MIN_WEIGHT }
            // Nothing at all to draw still needs an arc.
            .ifEmpty { listOf(Segment(Kind.SPENT, 1f)) }
    }

    /**
     * The order the segments are drawn in, clockwise from the gap: spent, the gap behind pace,
     * then what is left, ending at the arc's far end like a gauge running down.
     *
     * Some faces - the user's Pixel face among them - ignore the colours given and paint the
     * segments from their own palette by position: light blue, red, green. In this order that
     * reads green for money left and red for behind. The first order read green for spent.
     */
    fun drawOrder(segments: List<Segment>): List<Segment> {
        val rank = mapOf(Kind.SPENT to 0, Kind.BEHIND to 1, Kind.LEFT to 2)
        return segments.sortedBy { rank.getValue(it.kind) }
    }

    /** The colour for the single-colour arc faces without segments fall back to. */
    enum class Pace { ON_TRACK, TIGHT, OVER }

    fun pace(name: String?): Pace = Pace.entries.firstOrNull { it.name == name } ?: Pace.ON_TRACK
}
