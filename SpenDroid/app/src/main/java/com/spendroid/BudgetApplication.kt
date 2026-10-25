package com.spendroid

import android.app.Application
import androidx.room.Room
import com.spendroid.data.GoCardlessRepository
import com.spendroid.data.db.BudgetDb
import com.spendroid.work.DailyRoundupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class BudgetApplication : Application() {

    lateinit var repository: GoCardlessRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after is kept for the user to send.
        com.spendroid.data.CrashLog.install(this, BuildConfig.VERSION_NAME)
        // PDF statements: the reader finds its fonts and glyph lists through the app.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        // Encrypted, or plain - and a change the user asked for made now, before anything opens it.
        val passphrase = com.spendroid.data.DbEncryption.prepare(this)
        val db = Room.databaseBuilder(this, BudgetDb::class.java, com.spendroid.data.DbEncryption.NAME)
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
                BudgetDb.MIGRATION_12_13,
                BudgetDb.MIGRATION_13_14,
                BudgetDb.MIGRATION_14_15,
                BudgetDb.MIGRATION_15_16,
                BudgetDb.MIGRATION_16_17,
                BudgetDb.MIGRATION_17_18,
                BudgetDb.MIGRATION_18_19,
                BudgetDb.MIGRATION_19_20,
                BudgetDb.MIGRATION_20_21,
                BudgetDb.MIGRATION_21_22,
                BudgetDb.MIGRATION_22_23,
                BudgetDb.MIGRATION_23_24,
            )
            .apply {
                if (passphrase != null) {
                    System.loadLibrary("sqlcipher")
                    openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase))
                }
            }
            .build()
        repository = GoCardlessRepository.create(this, db.budgetDao())
        // The user's decisions, from the database - payee names once they're in.
        com.spendroid.data.Choices.start(this, db.budgetDao()) { com.spendroid.data.PayeeNames.load(this) }
        DailyRoundupScheduler.schedule(this)
        com.spendroid.data.AutoBackup.schedule(this)
        // An update kept to install, now installed: the file and its notification go.
        com.spendroid.data.ApkInstaller.tidy(this)
        // Credentials saved before 3.6 were in the clear; seal them. A failure leaves them
        // readable as they were, and it tries again next start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.sealLegacySecrets() }
            // Opened as it is now: the copy kept from before an encryption change can go.
            runCatching { db.openHelper.writableDatabase }.onSuccess { com.spendroid.data.DbEncryption.opened(this@BudgetApplication) }
        }
    }
}