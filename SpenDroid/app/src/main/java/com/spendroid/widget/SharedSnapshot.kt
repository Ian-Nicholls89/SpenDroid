package com.spendroid.widget

import android.content.Context
import com.spendroid.BudgetApplication
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One budget worked out per refresh, shared by every widget and the watch.
 *
 * Each worked it out for itself - four times a refresh, each reading every transaction - for
 * figures that were the same four times. A refresh now works it out once, fresh, and whatever
 * draws in the seconds after reuses it; anything later works it out again.
 */
internal object SharedSnapshot {

    private val lock = Mutex()
    private var held: BudgetSnapshot? = null
    /** The history the budget was worked out from, for the widgets' rows and the watch's list. */
    private var heldTransactions: List<TransactionEntity> = emptyList()
    private var heldAt = 0L

    /** Works the budget out now and keeps it for what draws next. Called at the start of a refresh. */
    suspend fun fresh(context: Context): BudgetSnapshot? = lock.withLock { compute(context) }

    /** Keeps a budget the app has just worked out, from [transactions], for what draws next. */
    suspend fun hold(snapshot: BudgetSnapshot, transactions: List<TransactionEntity>) = lock.withLock {
        held = snapshot
        heldTransactions = transactions
        heldAt = System.currentTimeMillis()
    }

    /** The budget from the refresh just made, or a new one if that was not moments ago. */
    suspend fun get(context: Context): BudgetSnapshot? = lock.withLock {
        if (System.currentTimeMillis() - heldAt < REUSE_MS) held else compute(context)
    }

    /** The history behind [get]'s budget: each widget and the watch read it in full before. */
    suspend fun transactions(context: Context): List<TransactionEntity> = lock.withLock {
        if (System.currentTimeMillis() - heldAt >= REUSE_MS) compute(context)
        heldTransactions
    }

    private suspend fun compute(context: Context): BudgetSnapshot? {
        val app = context.applicationContext as? BudgetApplication ?: return null
        val repo = app.repository
        val all = repo.transactions()
        // As budgetSnapshot(): no history, no budget.
        val snapshot = if (all.isEmpty()) null else repo.budgetSnapshotOf(all, repo.budgetTime())
        held = snapshot
        heldTransactions = all
        heldAt = System.currentTimeMillis()
        return snapshot
    }

    /** Long enough for one refresh's widgets to draw; short enough that nothing waits on it stale. */
    private const val REUSE_MS = 10_000L
}
