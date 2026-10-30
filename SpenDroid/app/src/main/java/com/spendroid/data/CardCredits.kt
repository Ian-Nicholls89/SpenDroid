package com.spendroid.data

import android.content.Context
import androidx.core.content.edit

/**
 * Money that came onto a card and wasn't paying it off: what the user said it was (0.8.0). A
 * refund comes off the month's card spending; a payment to the card from elsewhere doesn't. Kept
 * by the transaction's "accountId|transactionId", with the user's other choices.
 */
class CardCredits(context: Context) {

    private val prefs = Stores.prefs(context, Stores.CARD_CREDITS)

    /** Each answered credit: true for a refund. */
    fun answers(): Map<String, Boolean> = prefs.all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()

    fun set(key: String, refund: Boolean) = prefs.edit { putBoolean(key, refund) }
}
