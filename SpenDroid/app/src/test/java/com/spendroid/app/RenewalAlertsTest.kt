package com.spendroid.app

import android.Manifest
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.data.RenewalStore
import com.spendroid.domain.Renewals
import com.spendroid.work.RenewalAlerts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 0.7.2: a renewal's notification as the overnight check posts it, and its two buttons as the
 * phone delivers them - saved, the notification gone, and told again only after a snooze ends.
 * Made-up names and figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenewalAlertsTest {

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val today = LocalDate.now()
    private val car = Renewals.Renewal(id = "car", name = "Car insurance", ends = today.plusWeeks(6), lastMinor = 46_218)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // The app loads the user's choices as it starts: saved after that, or the load overwrites it.
        runBlocking { com.spendroid.data.Choices.awaitLoaded() }
        RenewalStore(app).save(car)
    }

    private fun check(on: LocalDate = today) = RenewalAlerts.check(app, Renewals.all(RenewalStore(app).all(), emptyList(), on))

    /** A button pressed: the broadcast it would send, delivered. */
    private fun press(label: String) {
        val n = manager.allNotifications.single()
        val action = n.actions.single { it.title == label }
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals("com.spendroid.work.RenewalReceiver", intent.component?.className)
        runBlocking { RenewalAlerts.answer(app, intent) }
    }

    @Test
    fun `told once, six weeks before, with both answers and the rough last price`() {
        check()
        val n = manager.allNotifications.single()
        assertEquals("Car insurance ends on ${car.ends.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))}", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Six weeks to go - time to compare quotes. Last time about £460.", n.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString())
        assertEquals(listOf("Remind me in 2 weeks", "Sorted"), n.actions.map { it.title.toString() })
        assertEquals("car", shadowOf(n.contentIntent).savedIntent.getStringExtra(RenewalAlerts.EXTRA_OPEN_RENEWAL))

        // The next night: not again.
        app.getSystemService(NotificationManager::class.java).cancelAll()
        check(today.plusDays(1))
        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun `remind me in 2 weeks - gone, quiet a fortnight, then told again`() {
        check()
        press("Remind me in 2 weeks")
        assertEquals(today.plusWeeks(2), RenewalStore(app).get("car")!!.snoozedUntil)
        assertTrue("the notification goes", manager.allNotifications.isEmpty())

        check(today.plusDays(13))
        assertTrue(manager.allNotifications.isEmpty())
        check(today.plusDays(14))
        assertTrue("told again", manager.allNotifications.isNotEmpty())
    }

    @Test
    fun `sorted - gone, and nothing more about it`() {
        check()
        press("Sorted")
        assertTrue(RenewalStore(app).get("car")!!.sorted)
        assertTrue(manager.allNotifications.isEmpty())
        check(today.plusWeeks(3))
        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun `not before its reminder`() {
        RenewalStore(app).save(car.copy(ends = today.plusWeeks(10)))
        check()
        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun `not when it looks paid already - Penny's question does instead`() {
        val paid = com.spendroid.data.db.TransactionEntity(
            accountId = "a", transactionId = "t1", bookingDate = today.minusDays(2).toString(), valueDate = null, amountMinor = -46_218,
            currency = "GBP", payee = "MADE-UP INSURANCE CO", description = null, isPending = false, rawJson = null,
        )
        RenewalAlerts.check(app, Renewals.all(RenewalStore(app).all(), listOf(paid), today))
        assertTrue(manager.allNotifications.isEmpty())
    }
}
