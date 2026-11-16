package com.follow.clash.autotunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.follow.clash.R
import com.follow.clash.RunState
import com.follow.clash.ServiceState
import com.follow.clash.common.Components
import com.follow.clash.common.GlobalState

/**
 * A VPN interface nothing reads, so whatever it captures goes nowhere. Starting the real tunnel
 * replaces it: Android hands an app's VPN over to the newest interface it establishes.
 */
class KillSwitchService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var apps: List<String>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val requested = intent?.getStringArrayListExtra(EXTRA_APPS)?.toList()
        val all = intent?.getBooleanExtra(EXTRA_ALL, true) ?: true
        val next = if (all) null else requested.orEmpty()
        if (tun != null && next == apps) return START_NOT_STICKY
        // Establishing over a tunnel that just came up would replace it.
        if (ServiceState.runState.value != RunState.STOPPED || prepare(this) != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val builder = Builder()
            .setSession(getString(R.string.kill_switch_title))
            .addAddress("10.255.255.254", 32)
            .addAddress("fdfe:dcba:9876::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        if (next == null) {
            runCatching { builder.addDisallowedApplication(packageName) }
        } else {
            val added = next.count { app ->
                try {
                    builder.addAllowedApplication(app)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            // With no allowed app, a VPN captures every app.
            if (added == 0) {
                close()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val established = runCatching { builder.establish() }.getOrNull()
        close()
        tun = established
        apps = next
        if (established == null) stopSelf()
        GlobalState.log("Kill switch: blocking ${next?.joinToString() ?: "every app"}")
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        close()
        stopSelf()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun close() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.kill_switch_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent().setComponent(Components.mainActivity),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.follow.clash.service.R.drawable.ic)
            .setContentTitle(getString(R.string.kill_switch_title))
            .setContentText(getString(R.string.kill_switch_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
    }

    companion object {
        private const val CHANNEL = "kill_switch"
        private const val NOTIFICATION_ID = 8
        private const val EXTRA_APPS = "apps"
        private const val EXTRA_ALL = "all"

        /** A null [apps] blocks every app but Quartz. */
        fun block(context: Context, apps: List<String>?) {
            val intent = Intent(context, KillSwitchService::class.java)
                .putExtra(EXTRA_ALL, apps == null)
                .putStringArrayListExtra(EXTRA_APPS, ArrayList(apps.orEmpty()))
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun release(context: Context) {
            context.stopService(Intent(context, KillSwitchService::class.java))
        }
    }
}
