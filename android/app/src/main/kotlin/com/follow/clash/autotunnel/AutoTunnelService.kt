package com.follow.clash.autotunnel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.follow.clash.R
import com.follow.clash.ServiceState
import com.follow.clash.common.Components
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

/**
 * Watches the network underneath the VPN and connects or disconnects by the auto-tunnel rules.
 * It acts when the network changes (or the rules do), so a manual toggle holds until then.
 */
class AutoTunnelService : Service() {
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val networks = mutableMapOf<Network, NetworkCapabilities>()
    private var lastKey: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val evaluate = Runnable { apply() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // New rules: judge the current network again.
        lastKey = null
        schedule()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(evaluate)
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        super.onDestroy()
    }

    private fun schedule() {
        handler.removeCallbacks(evaluate)
        // Networks come and go in bursts while switching; judge once they settle.
        handler.postDelayed(evaluate, SETTLE_MS)
    }

    private fun register() {
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        val cb =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Watcher(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
            else Watcher()
        callback = cb
        connectivity.registerNetworkCallback(request, cb)
    }

    /** Reports the non-VPN networks; Android 12+ only reveals the Wi-Fi name with the flag. */
    private inner class Watcher : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handler.post {
                networks[network] = caps
                schedule()
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                networks.remove(network)
                schedule()
            }
        }
    }

    private fun snapshot(): NetworkSnapshot {
        val validated = networks.values.filter {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.ifEmpty { networks.values.toList() }
        validated.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            ?.let { return NetworkSnapshot(NetworkKind.ETHERNET) }
        validated.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?.let { return NetworkSnapshot(NetworkKind.WIFI, ssid = ssidOf(it)) }
        validated.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            ?.let { return NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = subscriptionOf(it)) }
        return NetworkSnapshot(NetworkKind.NONE)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun ssidOf(caps: NetworkCapabilities): String? {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? WifiInfo
            else null
        val raw = info?.ssid ?: getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        return raw?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    }

    private fun subscriptionOf(caps: NetworkCapabilities): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.let { return it.subscriptionId }
            return SubscriptionManager.getActiveDataSubscriptionId()
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }
        return SubscriptionManager.getDefaultDataSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }

    private fun apply() {
        val settings = AutoTunnel.settings(this)
        if (!settings.enabled) {
            stopSelf()
            return
        }
        val network = snapshot()
        val key = "${network.kind}|${network.ssid}|${network.subscriptionId}"
        if (key == lastKey) return
        lastKey = key
        val connect = AutoTunnelRules.decide(settings, network) ?: return
        GlobalState.log("Auto-tunnel: $network -> ${if (connect) "connect" else "disconnect"}")
        GlobalState.launch {
            if (connect) ServiceState.handleStartAction() else ServiceState.handleStopAction()
        }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.auto_tunnel_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
            )
        }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent().setComponent(Components.mainActivity),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(com.follow.clash.service.R.drawable.ic)
                .setContentTitle(getString(R.string.auto_tunnel_title))
                .setContentText(getString(R.string.auto_tunnel_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
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

    private companion object {
        const val CHANNEL = "auto_tunnel"
        const val NOTIFICATION_ID = 7
        const val SETTLE_MS = 2_000L
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
