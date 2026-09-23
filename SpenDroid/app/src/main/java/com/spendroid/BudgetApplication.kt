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
            .addMigrations(
                BudgetDb.MIGRATION_1_2,
                BudgetDb.MIGRATION_2_3,
                BudgetDb.MIGRATION_3_4,
                BudgetDb.MIGRATION_4_5,
                BudgetDb.MIGRATION_5_6,
                BudgetDb.MIGRATION_6_7,
                BudgetDb.MIGRATION_7_8,
                BudgetDb.MIGRATION_8_9,
                BudgetDb.MIGRATION_9_10,
                BudgetDb.MIGRATION_10_11,
                BudgetDb.MIGRATION_11_12,
            )
            .build()
        repository = GoCardlessRepository.create(this, db.budgetDao())
        DailyRoundupScheduler.schedule(this)
    }
}