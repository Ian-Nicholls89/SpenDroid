package com.spendroid.domain

/**
 * How "available to spend" should be worked out.
 *
 * There is no single right answer, and which one suits can change month to month, so this is
 * the user's choice rather than the app's.
 */
enum class BudgetModel(val label: String, val explanation: String) {
    /**
     * Each cycle starts fresh from income minus commitments. Predictable, and an error never
     * compounds - but it ignores whatever was actually left over or overdrawn.
     */
    FRESH_START(
        "Fresh start each cycle",
        "Every payday resets to income minus your commitments. What was left over last cycle " +
            "is not counted.",
    ),

    /**
     * Whatever was in the pot when the cycle began is added to the budget, so a surplus
     * carries forward and a shortfall follows you.
     */
    ROLLOVER(
        "Carry the balance over",
        "What you started the cycle with is added to this cycle's budget, so a surplus " +
            "carries forward and a shortfall follows you.",
    ),

    /**
     * The budget and the balance shown next to each other rather than combined. Neither
     * number is adjusted, so the gap between plan and reality stays visible.
     */
    SHOW_BOTH(
        "Show budget and balance",
        "Shows the cycle's budget and what is actually in the account side by side, without " +
            "blending them into one figure.",
    ),
    ;

    companion object {
        fun from(name: String?): BudgetModel =
            entries.firstOrNull { it.name == name } ?: FRESH_START
    }
}
