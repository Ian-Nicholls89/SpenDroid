package com.spendroid.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Open on my computer": the page, served while the user has it on, and only over Wi-Fi. A
 * notification says so and stops it. It stops by itself when the Wi-Fi goes, and after 15
 * minutes with no visit, so it is never left running by accident.
 */
class WebService : Service() {

    private var server: WebServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_NOT_STICKY
        val address = wifiAddress(this)
        if (address == null) {
            WebAccess.stopped()
            stopSelf()
            return START_NOT_STICKY
        }
        val started = PORTS.firstNotNullOfOrNull { port ->
            runCatching { WebServer(applicationContext, address, port).also { it.start(SOCKET_TIMEOUT, false) } }.getOrNull()?.let { it to port }
        }
        if (started == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        server = started.first
        WebAccess.started("$address:${started.second}")
        startForegroundCompat(notification())
        // The notification follows the code, which changes as it is used.
        scope.launch { WebAccess.running.collectLatest { if (it != null) notify(notification()) } }
        watchWifi()
        handler.postDelayed(idleCheck, IDLE_CHECK_MS)
        return START_NOT_STICKY
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - WebAccess.lastActivity > IDLE_MS) stopSelf() else handler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    private fun watchWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (wifiAddress(this@WebService) == null) handler.post { stopSelf() }
            }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        server?.stop()
        server = null
        WebAccess.stopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Open on my computer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while SpenDroid's page is on for your computer"
            },
        )
        val running = WebAccess.running.value
        val stop = PendingIntent.getBroadcast(this, 0, Intent(this, WebStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("SpenDroid is open on your Wi-Fi")
            .setContentText(running?.let { "http://${it.address} · code ${it.code.chunked(3).joinToString(" ")}" } ?: "Starting")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "web_page"
        private const val NOTIFICATION_ID = 5101
        private val PORTS = 8765..8770
        private const val SOCKET_TIMEOUT = 15_000
        private const val IDLE_MS = 15 * 60 * 1000L
        private const val IDLE_CHECK_MS = 60 * 1000L

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WebService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, WebService::class.java))

        /** This phone's address on the Wi-Fi it is on, or null when it is not on Wi-Fi. */
        fun wifiAddress(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null
            return cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }
    }
}

/** The notification's Stop. */
class WebStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WebService.stop(context)
    }
}
