package com.spendroid.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** What the watch sends back to the phone. The phone's WatchMessageService answers to the same path. */
internal object PhoneLink {

    private const val CATEGORY_PATH = "/spendroid/category"
    private const val SEP = "\u001F"

    /** Puts a recent payment in [category] on the phone. False when no phone could be reached. */
    suspend fun setCategory(context: Context, recentId: String, category: String): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) return false
        val payload = (recentId + SEP + category).toByteArray(Charsets.UTF_8)
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, CATEGORY_PATH, payload).await() }
        true
    }.getOrDefault(false)
}
