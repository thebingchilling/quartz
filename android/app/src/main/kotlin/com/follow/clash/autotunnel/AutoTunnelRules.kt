package com.follow.clash.autotunnel

data class TunnelTarget(
    val profileId: Long = 0,
    val group: String? = null,
    val proxy: String? = null,
) {
    val key: String get() = "$profileId/${group.orEmpty()}/${proxy.orEmpty()}"
}

data class WifiTunnel(val ssid: String = "", val target: TunnelTarget? = null)

data class SimTunnel(val simId: Int = 0, val target: TunnelTarget? = null)

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

/** Every field has a default, so Kotlin emits the no-arg constructor Gson needs to keep them for absent keys. */
data class AutoTunnelSettings(
    val enabled: Boolean = false,
    val defaultTunnel: TunnelTarget? = null,
    val tunnelOnWifi: Boolean = true,
    val trustedNetworks: List<String> = emptyList(),
    val wifiTunnels: List<WifiTunnel> = emptyList(),
    val tunnelOnMobileData: Boolean = true,
    val mobileSims: List<Int> = emptyList(),
    val mobileTunnel: TunnelTarget? = null,
    val simTunnels: List<SimTunnel> = emptyList(),
    val stopOnNoNetwork: Boolean = false,
    val debounceSeconds: Int = 2,
    val apps: List<AppSettings> = emptyList(),
    val killSwitchTunnels: List<Long> = emptyList(),
    val currentProfileId: Long? = null,
    val rendered: Map<String, RenderedTarget> = emptyMap(),
) {
    val watchedApps: Set<String>
        get() = apps.map { it.detected }.toSet()

    val killSwitchApps: List<String>
        get() = apps.filter { it.killSwitch }.map { it.app }

    val usesKillSwitch: Boolean
        get() = killSwitchTunnels.isNotEmpty() || apps.any { it.killSwitch }
}

enum class NetworkKind { WIFI, CELLULAR, NONE }

/** [ssid] is null when Android hides it (no location access). */
data class NetworkSnapshot(
    val kind: NetworkKind,
    val ssid: String? = null,
    val subscriptionId: Int? = null,
)

/** A null [target] while connecting is whichever profile is current. */
data class Decision(val key: String, val connect: Boolean, val target: TunnelTarget? = null)

/** Mirrors `decideAutoTunnel`, `blocksAll` and `stepOpenApps` in Dart; keep both in step. */
object AutoTunnelRules {
    fun ssidMatches(pattern: String, ssid: String): Boolean {
        if ('*' !in pattern && '?' !in pattern) return pattern == ssid
        val regex = pattern.map {
            when (it) {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(it.toString())
            }
        }.joinToString("")
        return Regex("^$regex$").matches(ssid)
    }

    fun isTrusted(settings: AutoTunnelSettings, network: NetworkSnapshot): Boolean {
        val ssid = network.ssid ?: return false
        return network.kind == NetworkKind.WIFI &&
            settings.trustedNetworks.any { ssidMatches(it, ssid) }
    }

    fun decide(
        settings: AutoTunnelSettings,
        network: NetworkSnapshot,
        openApps: List<String> = emptyList(),
    ): Decision? {
        fun connect(key: String, target: TunnelTarget?) =
            Decision(key, true, target ?: settings.defaultTunnel)

        fun disconnect(key: String) = Decision(key, false)

        for (open in openApps) {
            settings.apps.firstOrNull { it.detected == open }?.let { return connect(it.id, it.target) }
        }
        return when (network.kind) {
            NetworkKind.WIFI -> {
                val ssid = network.ssid
                when {
                    isTrusted(settings, network) -> disconnect("wifi:trusted")
                    !settings.tunnelOnWifi -> disconnect("wifi:off")
                    else -> {
                        val preferred = ssid?.let { name ->
                            settings.wifiTunnels.firstOrNull { it.target != null && ssidMatches(it.ssid, name) }
                        }
                        if (preferred == null) connect("wifi", null)
                        else connect("wifi:${preferred.ssid}", preferred.target)
                    }
                }
            }
            NetworkKind.CELLULAR -> {
                val simId = network.subscriptionId
                val counted = settings.mobileSims.isEmpty() || simId == null || simId in settings.mobileSims
                if (!settings.tunnelOnMobileData || !counted) return disconnect("mobile:off")
                val perSim = simId?.let { id -> settings.simTunnels.firstOrNull { it.simId == id }?.target }
                if (perSim != null) connect("mobile:$simId", perSim)
                else connect("mobile", settings.mobileTunnel)
            }
            NetworkKind.NONE -> if (settings.stopOnNoNetwork) disconnect("none") else null
        }
    }

    fun blocksAll(settings: AutoTunnelSettings, network: NetworkSnapshot, tunnelId: Long?): Boolean =
        tunnelId != null && tunnelId in settings.killSwitchTunnels && !isTrusted(settings, network)

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
