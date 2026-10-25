package com.spendroid

import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * The app as tests start it. On a phone, Android's start-up sets up WorkManager before the app
 * runs; Robolectric skips that, so the jobs the app books at start failed a moment later - blamed
 * on whichever test ran next. Set up here first, as the phone would - booking jobs, never running
 * them: the update check and the sync would reach for the internet mid-test, and keep the threads
 * the screens need. The jobs have tests of their own.
 */
class TestBudgetApplication : BudgetApplication() {
    override fun onCreate() {
        // Once per test process: Robolectric can keep one between tests.
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().setExecutor { /* booked, not run */ }.build())
        }
        super.onCreate()
    }
}
