package com.follow.clash.service.modules

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import com.follow.clash.core.Core
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Follows Android's own pick: a Wi-Fi without internet stays connected while traffic uses mobile data. */
internal class NetworkObserveModule(
    private val context: Context,
    private val ownsNetwork: Boolean = true,
    private val onUnderlyingNetwork: ((Network?) -> Boolean)? = null,
) : ServiceModule {

    private val connectivity by lazy {
        context.getSystemService<ConnectivityManager>()
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var bestNetwork: Network? = null

    @Volatile
    private var bestDns: List<InetAddress> = emptyList()
    private var currentDnsList = listOf<String>()
    private var lastNetwork: Network? = null
    private var reportedNetwork: Network? = null

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            bestNetwork = network
            bestDns = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                connectivity?.getLinkProperties(network)?.dnsServers.orEmpty()
            } else {
                emptyList()
            }
            scheduleUpdate()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network != bestNetwork) return
            bestDns = linkProperties.dnsServers
            scheduleUpdate()
        }

        override fun onLost(network: Network) {
            if (network != bestNetwork) return
            bestNetwork = null
            bestDns = emptyList()
            scheduleUpdate()
        }
    }

    private val updateTask = Runnable(::update)

    // A switch arrives as a burst, and a network that just came up has no DNS
    // until its link properties follow; the Core flushes its cache per list.
    private fun scheduleUpdate() {
        mainHandler.removeCallbacks(updateTask)
        mainHandler.postDelayed(updateTask, UPDATE_DELAY_MILLIS)
    }

    override fun start() {
        val connectivity = connectivity ?: return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                connectivity.registerBestMatchingNetworkCallback(request, callback, mainHandler)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                connectivity.requestNetwork(request, callback, mainHandler)

            else -> connectivity.requestNetwork(request, callback)
        }
    }

    @Synchronized
    private fun update() {
        val network = bestNetwork
        val dnsList = bestDns
            .map { address -> address.asSocketAddressText(DNS_PORT) }
            .distinct()
        if (dnsList != currentDnsList) {
            currentDnsList = dnsList
            Core.updateDNS(dnsList.joinToString(","))
        }
        if (!ownsNetwork) return
        val report = onUnderlyingNetwork
        if (report != null && network != reportedNetwork) {
            // Android refuses it until the VPN is established, which can come after the first update.
            if (report(network)) {
                reportedNetwork = network
            } else {
                mainHandler.postDelayed(updateTask, UNDERLYING_RETRY_MILLIS)
            }
        }
        if (network == null) return
        if (lastNetwork != null && lastNetwork != network) {
            Core.resetNetwork()
        }
        lastNetwork = network
    }

    override fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } finally {
            // The DNS list stays: SystemDnsWatcher keeps it current with no VPN running.
            bestNetwork = null
            bestDns = emptyList()
            lastNetwork = null
            reportedNetwork = null
        }
    }
}

private const val DNS_PORT = 53
private const val UPDATE_DELAY_MILLIS = 200L
private const val UNDERLYING_RETRY_MILLIS = 1_000L

private fun InetAddress.asSocketAddressText(port: Int): String = when (this) {
    is Inet6Address -> "[$hostAddress]:$port"
    is Inet4Address -> "$hostAddress:$port"
    else -> error("Unsupported address type: ${javaClass.name}")
}
