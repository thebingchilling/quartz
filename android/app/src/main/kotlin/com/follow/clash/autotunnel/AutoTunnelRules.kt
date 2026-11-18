package com.follow.clash.autotunnel

data class TunnelTarget(
    val profileId: Long = 0,
    val group: String? = null,
    val proxy: String? = null,
) {
    val key: String get() = "$profileId/${group.orEmpty()}/${proxy.orEmpty()}"
}

data class RuleApp(val app: String = "", val label: String = "", val detected: String = "")

/** A null [ssid] is any Wi-Fi and a null [simId] any SIM. */
data class NetworkMatch(val kind: String = "wifi", val ssid: String? = null, val simId: Int? = null) {
    fun matches(network: NetworkSnapshot): Boolean = when (kind) {
        "wifi" -> network.kind == NetworkKind.WIFI &&
            (ssid == null || (network.ssid != null && AutoTunnelRules.ssidMatches(ssid, network.ssid)))
        "mobile" -> network.kind == NetworkKind.CELLULAR && (simId == null || simId == network.subscriptionId)
        else -> false
    }
}

data class AutoTunnelRule(
    val id: String = "",
    val enabled: Boolean = true,
    val networks: List<NetworkMatch> = emptyList(),
    val apps: List<RuleApp> = emptyList(),
    val connect: Boolean = true,
    val target: TunnelTarget? = null,
) {
    fun matchesNetwork(network: NetworkSnapshot) = networks.isEmpty() || networks.any { it.matches(network) }
}

data class RenderedTarget(
    val profileId: Long = 0,
    val path: String = "",
    val label: String = "",
    val selectedMap: Map<String, String> = emptyMap(),
)

/** Every field has a default, so Kotlin emits the no-arg constructor Gson needs to keep them for absent keys. */
data class AutoTunnelSettings(
    val enabled: Boolean = false,
    val rules: List<AutoTunnelRule> = emptyList(),
    val defaultTunnel: TunnelTarget? = null,
    val disconnectOtherwise: Boolean = true,
    val stopOnNoNetwork: Boolean = true,
    val killSwitch: Boolean = false,
    val debounceSeconds: Int = 2,
    val currentProfileId: Long? = null,
    val rendered: Map<String, RenderedTarget> = emptyMap(),
) {
    val watchedApps: Set<String>
        get() = rules.filter { it.enabled }.flatMap { rule -> rule.apps.map { it.detected } }.toSet()
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

    fun decide(
        settings: AutoTunnelSettings,
        network: NetworkSnapshot,
        openApps: List<String> = emptyList(),
    ): Decision? {
        if (network.kind == NetworkKind.NONE) {
            return if (settings.stopOnNoNetwork) Decision("none", false) else null
        }
        fun act(rule: AutoTunnelRule) = Decision(
            "rule:${rule.id}",
            rule.connect,
            if (rule.connect) rule.target ?: settings.defaultTunnel else null,
        )
        val active = settings.rules.filter { it.enabled }
        for (open in openApps) {
            active.firstOrNull { rule -> rule.apps.any { it.detected == open } && rule.matchesNetwork(network) }
                ?.let { return act(it) }
        }
        active.firstOrNull { it.apps.isEmpty() && it.matchesNetwork(network) }?.let { return act(it) }
        return if (settings.disconnectOtherwise) Decision("otherwise", false) else null
    }

    fun blocksAll(settings: AutoTunnelSettings, tunnelWanted: Boolean): Boolean =
        settings.killSwitch && tunnelWanted

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
