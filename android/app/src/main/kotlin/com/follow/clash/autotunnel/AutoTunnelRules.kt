package com.follow.clash.autotunnel

import com.google.gson.annotations.SerializedName

enum class ConnectionKind {
    @SerializedName("wifi") WIFI,
    @SerializedName("mobile") MOBILE,
    @SerializedName("ethernet") ETHERNET,
}

/** No [profileId] means disconnect. */
data class TunnelTarget(
    val profileId: Long? = null,
    val group: String? = null,
    val proxy: String? = null,
)

/** A null [ssid] is every other Wi-Fi, a null [simId] every other SIM. */
data class ConnectionSettings(
    val id: String = "",
    val kind: ConnectionKind = ConnectionKind.WIFI,
    val ssid: String? = null,
    val simId: Int? = null,
    val target: TunnelTarget? = null,
    val killSwitch: Boolean = false,
)

data class AppSettings(
    val id: String = "",
    val app: String = "",
    val detected: String = "",
    val target: TunnelTarget? = null,
    val killSwitch: Boolean = false,
)

data class RenderedTarget(
    val profileId: Long = 0,
    val path: String = "",
    val label: String = "",
    val selectedMap: Map<String, String> = emptyMap(),
)

data class AutoTunnelSettings(
    val enabled: Boolean = false,
    val connections: List<ConnectionSettings> = emptyList(),
    val apps: List<AppSettings> = emptyList(),
    val killSwitchTunnels: List<Long> = emptyList(),
    val currentProfileId: Long? = null,
    val rendered: Map<String, RenderedTarget> = emptyMap(),
) {
    val watchedApps: Set<String>
        get() = apps.filter { it.target != null }.map { it.detected }.toSet()

    val killSwitchApps: List<String>
        get() = apps.filter { it.killSwitch }.map { it.app }

    val usesKillSwitch: Boolean
        get() = killSwitchTunnels.isNotEmpty() ||
            connections.any { it.killSwitch } ||
            apps.any { it.killSwitch }
}

enum class NetworkKind { WIFI, CELLULAR, ETHERNET, NONE }

/** [ssid] is null when Android hides it (no location access). */
data class NetworkSnapshot(
    val kind: NetworkKind,
    val ssid: String? = null,
    val subscriptionId: Int? = null,
)

data class Decision(val key: String, val target: TunnelTarget)

/** Mirrors `decideAutoTunnel`, `blocksAll` and `stepOpenApps` in Dart; keep both in step. */
object AutoTunnelRules {
    fun connectionFor(settings: AutoTunnelSettings, network: NetworkSnapshot): ConnectionSettings? {
        val kind = when (network.kind) {
            NetworkKind.WIFI -> ConnectionKind.WIFI
            NetworkKind.CELLULAR -> ConnectionKind.MOBILE
            NetworkKind.ETHERNET -> ConnectionKind.ETHERNET
            NetworkKind.NONE -> return null
        }
        val ofKind = settings.connections.filter { it.kind == kind }
        val named = when {
            kind == ConnectionKind.WIFI && network.ssid != null ->
                ofKind.firstOrNull { it.ssid == network.ssid }
            kind == ConnectionKind.MOBILE && network.subscriptionId != null ->
                ofKind.firstOrNull { it.simId == network.subscriptionId }
            else -> null
        }
        return named ?: ofKind.firstOrNull { it.ssid == null && it.simId == null }
    }

    fun decide(
        settings: AutoTunnelSettings,
        network: NetworkSnapshot,
        openApps: List<String> = emptyList(),
    ): Decision? {
        for (open in openApps) {
            settings.apps.firstOrNull { it.detected == open && it.target != null }
                ?.let { return Decision(it.id, it.target!!) }
        }
        val connection = connectionFor(settings, network) ?: return null
        val target = connection.target ?: return null
        return Decision(connection.id, target)
    }

    fun blocksAll(settings: AutoTunnelSettings, network: NetworkSnapshot, tunnelId: Long?): Boolean =
        (tunnelId != null && tunnelId in settings.killSwitchTunnels) ||
            connectionFor(settings, network)?.killSwitch == true

    fun stepOpenApps(
        recent: List<String>,
        foreground: String?,
        open: Set<String>?,
        watched: Set<String>,
    ): List<String> {
        val next =
            if (foreground != null && foreground in watched) listOf(foreground) + (recent - foreground)
            else recent
        return next.filter { app ->
            app in watched && (app == foreground || (open != null && app in open))
        }
    }
}
