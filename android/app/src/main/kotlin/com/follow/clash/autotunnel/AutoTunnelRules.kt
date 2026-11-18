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
    val name: String = "",
    val enabled: Boolean = true,
    val categoryId: String? = null,
    val networks: List<NetworkMatch> = emptyList(),
    val apps: List<RuleApp> = emptyList(),
    val matchAll: Boolean = true,
    val connect: Boolean = true,
    val target: TunnelTarget? = null,
    val killSwitch: Boolean = false,
) {
    fun matchesNetwork(network: NetworkSnapshot) = networks.isNotEmpty() && networks.any { it.matches(network) }

    /** Whether this rule holds with [app] open, or with no rule app open when [app] is null. */
    fun matches(network: NetworkSnapshot, app: String?): Boolean {
        val appOpen = app != null && apps.any { it.detected == app }
        if (apps.isEmpty()) return networks.isEmpty() || matchesNetwork(network)
        if (networks.isEmpty()) return appOpen
        return if (matchAll) appOpen && matchesNetwork(network) else appOpen || matchesNetwork(network)
    }
}

data class RuleCategory(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
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
    val rules: List<AutoTunnelRule> = emptyList(),
    val categories: List<RuleCategory> = emptyList(),
    val currentProfileId: Long? = null,
    val rendered: Map<String, RenderedTarget> = emptyMap(),
) {
    private fun categoryOf(rule: AutoTunnelRule) = categories.firstOrNull { it.id == rule.categoryId }

    fun isActive(rule: AutoTunnelRule) = rule.enabled && (categoryOf(rule)?.enabled ?: true)

    fun killSwitchOf(rule: AutoTunnelRule) =
        rule.connect && (rule.killSwitch || (categoryOf(rule)?.killSwitch ?: false))

    val activeRules: List<AutoTunnelRule> get() = rules.filter(::isActive)

    fun activeRule(id: String?): AutoTunnelRule? = id?.let { activeRules.firstOrNull { rule -> rule.id == it } }

    val watchedApps: Set<String>
        get() = activeRules.flatMap { rule -> rule.apps.map { it.detected } }.toSet()
}

enum class NetworkKind { WIFI, CELLULAR, NONE }

/** [ssid] is null when Android hides it (no location access). */
data class NetworkSnapshot(
    val kind: NetworkKind,
    val ssid: String? = null,
    val subscriptionId: Int? = null,
)

/** [ruleId] is the rule now in effect, null when none is. */
data class Decision(
    val key: String,
    val connect: Boolean,
    val target: TunnelTarget? = null,
    val ruleId: String? = null,
)

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
        active: String? = null,
    ): Decision? {
        if (network.kind == NetworkKind.NONE) {
            val rule = settings.activeRule(active)
            if (rule != null && settings.killSwitchOf(rule)) return null
            return Decision("none", false)
        }
        fun act(rule: AutoTunnelRule) = Decision(
            "rule:${rule.id}",
            rule.connect,
            if (rule.connect) rule.target else null,
            rule.id,
        )
        val rules = settings.activeRules
        for (open in openApps) {
            rules.firstOrNull { rule -> rule.apps.any { it.detected == open } && rule.matches(network, open) }
                ?.let { return act(it) }
        }
        rules.firstOrNull { it.matches(network, null) }?.let { return act(it) }
        return if (active == null) null else Decision("ended", false)
    }

    fun blocksAll(settings: AutoTunnelSettings, active: String?, running: Boolean): Boolean {
        if (running || !settings.enabled) return false
        val rule = settings.activeRule(active) ?: return false
        return settings.killSwitchOf(rule)
    }

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
