package com.budgetapp

import android.app.Application
import androidx.room.Room
import com.budgetapp.data.GoCardlessRepository
import com.budgetapp.data.db.BudgetDb
import com.budgetapp.work.DailyRoundupScheduler

class BudgetApplication : Application() {

    lateinit var repository: GoCardlessRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, BudgetDb::class.java, "budget.db")
            .addMigrations(BudgetDb.MIGRATION_1_2, BudgetDb.MIGRATION_2_3, BudgetDb.MIGRATION_3_4)
            .build()
        repository = GoCardlessRepository.create(this, db.budgetDao())
        val versionCode = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        } catch (e: Exception) {
            0
        }
        DailyRoundupScheduler.schedule(this, versionCode)
    }
}