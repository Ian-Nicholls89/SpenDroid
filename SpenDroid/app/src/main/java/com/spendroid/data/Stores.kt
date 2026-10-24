package com.spendroid.data

/**
 * Everything SpenDroid keeps on the phone outside its database, in one list - and what becomes of
 * each: carried in SpenDroid's own backup, cleared by "Delete all data", or left out of Android's
 * own backup and phone-to-phone transfer.
 *
 * Every preferences file the app opens is named here and nowhere else, so a new one has to be
 * placed: the backup, the reset and the backup rules (res/xml) follow from the list, and a test
 * fails if a file is opened that isn't on it, or if res/xml disagrees with it.
 */
object Stores {

    /** What a store holds, which decides its fate. */
    enum class Kind {
        /** The user's money, or what they've told Penny about it: goes when the data does. */
        USER_DATA,

        /** How this phone is set up - the lock, the icon, the watch, widgets: kept through a reset. */
        DEVICE,
    }

    class Store(
        val name: String,
        val kind: Kind,
        /**
         * Carried in SpenDroid's own backup, and put back by a restore: all the user's data, and
         * the settings of this phone that mean the same on another (not widget ids, pairings,
         * keys or anything sealed by this phone's keystore).
         */
        val inBackup: Boolean = false,
        /** Left out of Android's own backup: it can't work on another phone, or mustn't leave this one. */
        val phoneOnly: Boolean = false,
        val holds: String,
    )

    // ---- the user's data ----------------------------------------------------------------------
    const val FIRST_RUN = "first_run"
    const val MOMENTS = "moments"
    const val CONTRACTS = "contracts"
    const val SUBSCRIPTIONS = "subscriptions"
    const val MONEY_WATCH = "money_watch"
    const val MANUAL_REMINDERS = "manual_reminders"
    const val PAYEE_NAMES = "payee_names"
    const val PENNY_ASK = "penny_ask"
    const val PENNY_BILLS = "penny_bills"
    const val PENNY_NEWS = "penny_news"
    const val PENNY_VOICE = "penny_voice"

    // ---- this phone ---------------------------------------------------------------------------
    const val PRIVACY = "privacy"
    const val LAUNCHER_ICON = "launcher_icon"
    const val WATCH_INSTALL = "watch_install"
    const val WATCH_CHIME = "watch_chime"
    const val THIS_PERIOD_WIDGET = "this_period_widget"
    const val WIDGET_REFRESH = "widget_refresh"
    const val WEB_IMPORT = "web_import"
    const val WEB_ACCESS = "web_access"
    const val DB_ENCRYPTION = "db_encryption"
    const val AUTO_BACKUP = "auto_backup"

    val ALL: List<Store> = listOf(
        Store(FIRST_RUN, Kind.USER_DATA, inBackup = true, holds = "Whether setup is finished"),
        Store(MOMENTS, Kind.USER_DATA, inBackup = true, holds = "Whether this cycle's payday moment has been shown"),
        Store(CONTRACTS, Kind.USER_DATA, inBackup = true, holds = "Contract end dates and accepted prices"),
        Store(SUBSCRIPTIONS, Kind.USER_DATA, inBackup = true, holds = "Subscription or household bill, reminders, cancelled"),
        Store(MONEY_WATCH, Kind.USER_DATA, inBackup = true, holds = "Money watch settings, and price rises already told or decided"),
        Store(MANUAL_REMINDERS, Kind.USER_DATA, inBackup = true, holds = "How often to remind about statements and balance checks"),
        Store(PAYEE_NAMES, Kind.USER_DATA, inBackup = true, holds = "Names given to payees"),
        Store(PENNY_ASK, Kind.USER_DATA, inBackup = true, holds = "Questions put off with \"Ask me later\""),
        Store(PENNY_BILLS, Kind.USER_DATA, inBackup = true, holds = "Answers to \"Is this a bill?\""),
        Store(PENNY_NEWS, Kind.USER_DATA, inBackup = true, holds = "Card digits Penny has already said she learnt"),
        Store(PENNY_VOICE, Kind.USER_DATA, inBackup = true, holds = "A count of visits to Home, to vary Penny's lines"),

        Store(PRIVACY, Kind.DEVICE, inBackup = true, holds = "App lock, and where figures show (widgets, watch, notifications, recents)"),
        Store(LAUNCHER_ICON, Kind.DEVICE, inBackup = true, holds = "The chosen app icon"),
        Store(WATCH_INSTALL, Kind.DEVICE, holds = "Watch pairing and install"),
        Store(WATCH_CHIME, Kind.DEVICE, inBackup = true, holds = "The watch's chime"),
        Store(THIS_PERIOD_WIDGET, Kind.DEVICE, holds = "Which accounts each This period widget shows"),
        Store(WIDGET_REFRESH, Kind.DEVICE, holds = "When the widgets were last drawn, and a refresh under way"),
        Store(WEB_IMPORT, Kind.DEVICE, inBackup = true, holds = "Statement layouts and batches sent from the computer link"),
        Store(WEB_ACCESS, Kind.DEVICE, holds = "Computers the computer link remembers (hashed)"),
        Store(DB_ENCRYPTION, Kind.DEVICE, holds = "Database encryption: on, and its key sealed by this phone"),
        Store(AUTO_BACKUP, Kind.DEVICE, phoneOnly = true, holds = "Automatic backup: the folder, and the passphrase sealed by this phone"),
    )

    /**
     * Not preferences files, listed so nothing is left out of the picture: the database itself
     * (in Android's backup - history past the bank's 90 days exists nowhere else) and the settings
     * store, which holds the GoCardless secret and is never in Android's backup.
     */
    const val DATABASE = DbEncryption.NAME
    const val SETTINGS_FILE = "datastore/budget_settings.preferences_pb"

    /** Cleared by "Delete all data". */
    val clearedWithData: List<String> get() = ALL.filter { it.kind == Kind.USER_DATA }.map { it.name }

    /** Carried in SpenDroid's backup. */
    val inBackup: List<String> get() = ALL.filter { it.inBackup }.map { it.name }

    /** Left out of Android's backup and device transfer, as res/xml's rules say. */
    val phoneOnly: List<String> get() = ALL.filter { it.phoneOnly }.map { it.name }
}
