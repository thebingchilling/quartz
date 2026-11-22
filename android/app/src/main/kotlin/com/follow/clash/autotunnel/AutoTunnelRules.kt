package com.follow.clash.autotunnel

data class TunnelTarget(
    val profileId: Long = 0,
    val group: String? = null,
    val proxy: String? = null,
) {
    val key: String get() = "$profileId/${group.orEmpty()}/${proxy.orEmpty()}"
}

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
    val connect: Boolean = true,
    val target: TunnelTarget? = null,
    val killSwitch: Boolean = false,
    val onEnd: String = "disconnect",
    val endTarget: TunnelTarget? = null,
) {
    fun matchesNetwork(network: NetworkSnapshot) = networks.isEmpty() || networks.any { it.matches(network) }
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
}

enum class NetworkKind { WIFI, CELLULAR, NONE }

/** [ssid] is null when Android hides it (no location access). */
data class NetworkSnapshot(
    val kind: NetworkKind,
    val ssid: String? = null,
    val subscriptionId: Int? = null,
)

/** [ruleId] is the rule now in effect, null when none is; a null [connect] leaves the tunnel as it is. */
data class Decision(
    val key: String,
    val connect: Boolean?,
    val target: TunnelTarget? = null,
    val ruleId: String? = null,
)

/** Mirrors `decideAutoTunnel` and `blocksAll` in Dart; keep both in step. */
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
        settings.activeRules.firstOrNull { it.matchesNetwork(network) }?.let { return act(it) }
        if (active == null) return null
        val ended = settings.rules.firstOrNull { it.id == active }
        return when {
            ended?.onEnd == "keep" -> Decision("ended", null)
            ended?.onEnd == "connect" && ended.endTarget != null -> Decision("ended", true, ended.endTarget)
            else -> Decision("ended", false)
        }
    }

    fun blocksAll(settings: AutoTunnelSettings, active: String?, running: Boolean): Boolean {
        if (running || !settings.enabled) return false
        val rule = settings.activeRule(active) ?: return false
        return settings.killSwitchOf(rule)
    }
}
