package com.follow.clash.autotunnel

import com.google.gson.annotations.SerializedName

enum class TriggerKind {
    @SerializedName("wifi") WIFI,
    @SerializedName("mobile") MOBILE,
    @SerializedName("ethernet") ETHERNET,
    @SerializedName("app") APP,
}

enum class FallbackMode {
    @SerializedName("keep") KEEP,
    @SerializedName("disconnect") DISCONNECT,
    @SerializedName("target") TARGET,
}

/** No [profileId] means disconnect. */
data class TunnelTarget(
    val profileId: Long? = null,
    val group: String? = null,
    val proxy: String? = null,
)

data class AutoTunnelRule(
    val id: String = "",
    val kind: TriggerKind = TriggerKind.WIFI,
    val enabled: Boolean = true,
    val ssids: List<String> = emptyList(),
    val simIds: List<Int> = emptyList(),
    val apps: List<String> = emptyList(),
    val target: TunnelTarget = TunnelTarget(),
)

data class RenderedTarget(
    val profileId: Long = 0,
    val path: String = "",
    val label: String = "",
    val selectedMap: Map<String, String> = emptyMap(),
)

data class AutoTunnelSettings(
    val enabled: Boolean = false,
    val rules: List<AutoTunnelRule> = emptyList(),
    val fallback: FallbackMode = FallbackMode.KEEP,
    val fallbackTarget: TunnelTarget = TunnelTarget(),
    val rendered: Map<String, RenderedTarget> = emptyMap(),
) {
    val ruleApps: Set<String>
        get() = rules.filter { it.enabled && it.kind == TriggerKind.APP }
            .flatMap { it.apps }
            .toSet()
}

enum class NetworkKind { WIFI, CELLULAR, ETHERNET, NONE }

/** [ssid] is null when Android hides it (no location access). */
data class NetworkSnapshot(
    val kind: NetworkKind,
    val ssid: String? = null,
    val subscriptionId: Int? = null,
)

data class Decision(val key: String, val target: TunnelTarget)

object AutoTunnelRules {
    const val FALLBACK = "fallback"

    /** Null leaves the tunnel as it is. Mirrors `decideAutoTunnel` in Dart; keep both in step. */
    fun decide(
        settings: AutoTunnelSettings,
        network: NetworkSnapshot,
        openApps: List<String> = emptyList(),
    ): Decision? {
        val rules = settings.rules.filter { it.enabled }
        for (app in openApps) {
            rules.firstOrNull { it.kind == TriggerKind.APP && app in it.apps }
                ?.let { return Decision(it.id, it.target) }
        }
        if (network.kind == NetworkKind.NONE) return null
        rules.firstOrNull { holds(it, network) }?.let { return Decision(it.id, it.target) }
        return when (settings.fallback) {
            FallbackMode.KEEP -> null
            FallbackMode.DISCONNECT -> Decision(FALLBACK, TunnelTarget())
            FallbackMode.TARGET -> Decision(FALLBACK, settings.fallbackTarget)
        }
    }

    private fun holds(rule: AutoTunnelRule, network: NetworkSnapshot): Boolean =
        when (rule.kind) {
            TriggerKind.APP -> false
            TriggerKind.ETHERNET -> network.kind == NetworkKind.ETHERNET
            TriggerKind.MOBILE ->
                network.kind == NetworkKind.CELLULAR &&
                    (rule.simIds.isEmpty() || network.subscriptionId in rule.simIds)
            TriggerKind.WIFI ->
                network.kind == NetworkKind.WIFI &&
                    (rule.ssids.isEmpty() || network.ssid in rule.ssids)
        }

    /** A null [open] means only the foreground app is known to be open. */
    fun stepOpenApps(
        recent: List<String>,
        foreground: String?,
        open: Set<String>?,
        ruleApps: Set<String>,
    ): List<String> {
        val next =
            if (foreground != null && foreground in ruleApps) listOf(foreground) + (recent - foreground)
            else recent
        return next.filter { app ->
            app in ruleApps && (app == foreground || (open != null && app in open))
        }
    }
}
