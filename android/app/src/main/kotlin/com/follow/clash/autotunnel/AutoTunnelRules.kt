package com.follow.clash.autotunnel

import com.google.gson.annotations.SerializedName

enum class WifiMode {
    /** Connect on every Wi-Fi except the listed ones. */
    @SerializedName("blacklist")
    BLACKLIST,

    /** Connect only on the listed Wi-Fi. */
    @SerializedName("whitelist")
    WHITELIST,
}

data class AutoTunnelSettings(
    val enabled: Boolean = false,
    val wifiEnabled: Boolean = true,
    val wifiMode: WifiMode = WifiMode.BLACKLIST,
    val ssids: List<String> = emptyList(),
    val mobileEnabled: Boolean = true,
    /** Subscription IDs whose mobile data connects; empty means every SIM. */
    val simIds: List<Int> = emptyList(),
    val ethernetEnabled: Boolean = true,
)

enum class NetworkKind { WIFI, CELLULAR, ETHERNET, NONE }

/** The network underneath the VPN. [ssid] is null when Android hides it (no location access). */
data class NetworkSnapshot(
    val kind: NetworkKind,
    val ssid: String? = null,
    val subscriptionId: Int? = null,
)

object AutoTunnelRules {
    /** True to connect, false to disconnect, null to leave the tunnel as it is. */
    fun decide(settings: AutoTunnelSettings, network: NetworkSnapshot): Boolean? =
        when (network.kind) {
            NetworkKind.NONE -> null
            NetworkKind.ETHERNET -> settings.ethernetEnabled
            NetworkKind.CELLULAR ->
                settings.mobileEnabled &&
                    (settings.simIds.isEmpty() || network.subscriptionId in settings.simIds)
            NetworkKind.WIFI -> {
                if (!settings.wifiEnabled) {
                    false
                } else {
                    val ssid = network.ssid
                    val listed = ssid != null && settings.ssids.any { it == ssid }
                    when (settings.wifiMode) {
                        WifiMode.BLACKLIST -> if (ssid == null) null else !listed
                        WifiMode.WHITELIST -> listed
                    }
                }
            }
        }
}
