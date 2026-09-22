package com.spendroid

import android.app.Application
import androidx.room.Room
import com.spendroid.data.GoCardlessRepository
import com.spendroid.data.db.BudgetDb
import com.spendroid.work.DailyRoundupScheduler

class BudgetApplication : Application() {

    lateinit var repository: GoCardlessRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, BudgetDb::class.java, "budget.db")
            .addMigrations(BudgetDb.MIGRATION_1_2, BudgetDb.MIGRATION_2_3, BudgetDb.MIGRATION_3_4)
            .build()
        repository = GoCardlessRepository.create(this, db.budgetDao())
        DailyRoundupScheduler.schedule(this)
    }
}