package app.quartz.importer

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

/** Reading and writing Clash/mihomo YAML. */
object ClashYaml {
    private const val MAX_BYTES = 16 * 1024 * 1024

    /** The top-level mapping of [text], or null if it isn't a YAML mapping. */
    fun parseMapping(text: String): Map<String, Any?>? {
        if (text.length > MAX_BYTES) return null
        val settings =
            LoadSettings.builder()
                .setAllowDuplicateKeys(true)
                .setMaxAliasesForCollections(200)
                .build()
        @Suppress("UNCHECKED_CAST")
        return runCatching { Load(settings).loadFromString(text) as? Map<String, Any?> }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun proxies(mapping: Map<String, Any?>): List<ProxyNode> =
        (mapping["proxies"] as? List<*>).orEmpty().mapNotNull { entry ->
            (entry as? Map<String, Any?>)?.let { ProxyNode(it) }
        }

    fun isFullProfile(mapping: Map<String, Any?>): Boolean =
        "rules" in mapping || "proxy-groups" in mapping || "rule-providers" in mapping

    fun hasProxies(mapping: Map<String, Any?>): Boolean =
        "proxies" in mapping || "proxy-providers" in mapping

    fun dump(value: Map<String, Any?>): String =
        Dump(
                DumpSettings.builder()
                    .setDefaultFlowStyle(FlowStyle.BLOCK)
                    .setIndent(2)
                    .setIndicatorIndent(2)
                    .setIndentWithIndicator(true)
                    .setWidth(4096)
                    .build()
            )
            .dumpToString(value)

    /** Makes every proxy name unique ("name", "name 2", …), as mihomo requires. */
    fun uniqueNames(proxies: List<ProxyNode>): List<ProxyNode> {
        val used = mutableSetOf<String>()
        return proxies.map { proxy ->
            val base =
                (proxy["name"] as? String)?.ifBlank { null }
                    ?: "${proxy["server"]}:${proxy["port"]}"
            var name = base
            var n = 2
            while (!used.add(name)) name = "$base ${n++}"
            ProxyNode(proxy).apply { put("name", name) }
        }
    }
}

/** Profiles built around a bare proxy list (FORK.md §7, templates). */
sealed interface ClashTemplate {
    /** All traffic through a Select group, with an Auto (fastest) choice. */
    data object Simple : ClashTemplate

    /** Local and [countryCode] addresses direct, ads rejected, the rest through the proxy. */
    data class RuleBased(val countryCode: String) : ClashTemplate

    /** The user's YAML; `{{proxies}}` becomes the proxy list, `{{proxy-names}}` their names. */
    data class Custom(val yaml: String) : ClashTemplate

    companion object {
        const val TEST_URL = "https://cp.cloudflare.com/generate_204"
        const val PROXY_GROUP = "Proxy"
        const val AUTO_GROUP = "Auto"

        fun build(
            template: ClashTemplate,
            proxies: List<ProxyNode>,
            providers: Map<String, Any?> = emptyMap(),
        ): String {
            val nodes = ClashYaml.uniqueNames(proxies)
            val names = nodes.map { it["name"] as String }
            if (template is Custom) {
                val proxiesYaml =
                    ClashYaml.dump(mapOf("proxies" to nodes)).removePrefix("proxies:").trimEnd()
                return template.yaml
                    .replace("{{proxies}}", proxiesYaml)
                    .replace("{{proxy-names}}", names.joinToString(", ", "[", "]") { quote(it) })
            }
            val providerNames = providers.keys.toList()
            fun members(vararg extra: String) =
                linkedMapOf<String, Any?>().apply {
                    put("proxies", extra.toList() + names)
                    if (providerNames.isNotEmpty()) put("use", providerNames)
                }
            val profile =
                linkedMapOf<String, Any?>(
                    "mode" to "rule",
                    "log-level" to "info",
                    "ipv6" to true,
                    "dns" to defaultDns(),
                )
            if (nodes.isNotEmpty()) profile["proxies"] = nodes
            if (providers.isNotEmpty()) profile["proxy-providers"] = providers
            profile["proxy-groups"] =
                listOf(
                    linkedMapOf<String, Any?>("name" to PROXY_GROUP, "type" to "select") +
                        members(AUTO_GROUP, "DIRECT"),
                    linkedMapOf<String, Any?>(
                        "name" to AUTO_GROUP,
                        "type" to "url-test",
                        "url" to TEST_URL,
                        "interval" to 300,
                        "tolerance" to 50,
                    ) + members(),
                )
            profile["rules"] =
                when (template) {
                    is RuleBased ->
                        listOfNotNull(
                            "GEOSITE,private,DIRECT",
                            "GEOIP,private,DIRECT,no-resolve",
                            "GEOSITE,category-ads-all,REJECT",
                            template.countryCode
                                .takeIf { it.length == 2 }
                                ?.let { "GEOIP,${it.uppercase()},DIRECT" },
                            "MATCH,$PROXY_GROUP",
                        )
                    else -> listOf("MATCH,$PROXY_GROUP")
                }
            return ClashYaml.dump(profile)
        }

        fun defaultDns(): Map<String, Any?> =
            linkedMapOf(
                "enable" to true,
                "ipv6" to true,
                "enhanced-mode" to "fake-ip",
                "fake-ip-range" to "198.18.0.1/16",
                "default-nameserver" to listOf("1.1.1.1", "8.8.8.8"),
                "nameserver" to listOf("https://1.1.1.1/dns-query", "https://dns.google/dns-query"),
            )

        private fun quote(value: String) =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
