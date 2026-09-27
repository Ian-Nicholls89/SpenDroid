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

    /** The colour for the single-colour arc faces without segments fall back to. */
    enum class Pace { ON_TRACK, TIGHT, OVER }

    fun pace(name: String?): Pace = Pace.entries.firstOrNull { it.name == name } ?: Pace.ON_TRACK
}
