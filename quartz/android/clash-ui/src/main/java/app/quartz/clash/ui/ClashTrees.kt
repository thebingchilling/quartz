package app.quartz.clash.ui

import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MihomoState
import app.quartz.engine.mihomo.MihomoApi
import app.quartz.importer.ClashYaml
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A node: [type] is its protocol as mihomo or the profile names it; [server] its host. */
data class ClashNode(val name: String, val type: String, val server: String? = null) {
    val protocol: String
        get() = protocolName(type)

    /** Built-in outbounds (DIRECT, REJECT, …) have nothing to test. */
    val testable: Boolean
        get() = type.lowercase() !in BUILT_IN
}

/** A proxy group; [now] is its selected member while the tunnel runs. */
data class ClashGroup(
    val name: String,
    val type: String,
    val now: String?,
    val members: List<String>,
    val selectable: Boolean,
)

data class ClashTree(
    val groups: List<ClashGroup>,
    val nodes: Map<String, ClashNode>,
    /** From the running mihomo (selection works) rather than read from the profile. */
    val live: Boolean,
) {
    fun groupNames(): Set<String> = groups.map { it.name }.toSet()

    /** The group that starts open: the first one the user picks a node in (usually PROXY). */
    val mainGroup: String?
        get() = (groups.firstOrNull { it.selectable } ?: groups.firstOrNull())?.name

    /** Every node any group lists, once. */
    fun testableNodes(): List<ClashNode> =
        groups
            .flatMap { it.members }
            .distinct()
            .filter { it !in groupNames() }
            .mapNotNull { nodes[it] }
            .filter { it.testable }
}

/**
 * A Clash tunnel's proxy groups and nodes for the Tunnels list: live from mihomo while that
 * tunnel runs, otherwise read from its profile YAML (groups listing provider nodes show only the
 * nodes the profile names).
 */
class ClashTrees(
    private val profiles: ClashProfiles,
    private val runner: MihomoRunner,
    /** mihomo's home directory, where it caches proxy-provider downloads. */
    private val mihomoHome: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val trees = mutableMapOf<Int, MutableStateFlow<ClashTree?>>()

    init {
        // Connecting or disconnecting switches between live and profile data.
        scope.launch {
            runner.state
                .map { (it as? MihomoState.Running)?.tunnelId }
                .distinctUntilChanged()
                .collect { synchronized(trees) { trees.keys.toList() }.forEach { refresh(it) } }
        }
    }

    fun tree(tunnelId: Int): StateFlow<ClashTree?> = flow(tunnelId).asStateFlow()

    private fun flow(tunnelId: Int) =
        synchronized(trees) { trees.getOrPut(tunnelId) { MutableStateFlow(null) } }

    /** The running mihomo's API when [tunnelId] is the running Clash tunnel. */
    fun api(tunnelId: Int): MihomoApi? =
        (runner.state.value as? MihomoState.Running)
            ?.takeIf { it.tunnelId == tunnelId }
            ?.let { MihomoApi(it.controller) }

    fun refresh(tunnelId: Int) {
        scope.launch { load(tunnelId) }
    }

    suspend fun load(tunnelId: Int): ClashTree? {
        val tree = api(tunnelId)?.let { runCatching { live(it) }.getOrNull() } ?: offline(tunnelId)
        flow(tunnelId).value = tree
        return tree
    }

    fun select(tunnelId: Int, group: String, node: String, onSelected: () -> Unit) {
        val api = api(tunnelId) ?: return
        scope.launch {
            runCatching { api.select(group, node) }.onSuccess { onSelected() }
            load(tunnelId)
        }
    }

    private suspend fun live(api: MihomoApi): ClashTree {
        val proxies = api.proxies()
        val groups =
            proxies.groups
                .filter { !it.hidden && it.name != MihomoApi.GLOBAL }
                .map { ClashGroup(it.name, it.type, it.now, it.all, it.isSelectable) }
        val nodes =
            proxies.byName.values.filter { !it.isGroup }.associate { it.name to ClashNode(it.name, it.type) }
        return ClashTree(groups, nodes, live = true)
    }

    /**
     * The profile as written, without mihomo: inline proxies plus the nodes of its proxy
     * providers from mihomo's cache of them (so a subscription shows everything once it has run
     * at least once), groups with `use`, `filter`, `exclude-filter` and `include-all` applied.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun offline(tunnelId: Int): ClashTree? =
        withContext(ioDispatcher) {
            val profile = profiles.get(tunnelId) ?: return@withContext null
            val file: File = profiles.file(profile)
            val mapping =
                runCatching { ClashYaml.parseMapping(file.readText()) }.getOrNull()
                    ?: return@withContext null
            val inline = nodesOf(mapping)
            val providers: Map<String, List<ClashNode>> =
                (mapping["proxy-providers"] as? Map<String, Any?>).orEmpty().mapValues { (name, value) ->
                    providerNodes(name, value as? Map<String, Any?>)
                }
            val nodes = (inline + providers.values.flatten()).associateBy { it.name }
            val groupMaps =
                (mapping["proxy-groups"] as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }
            val groups =
                groupMaps.mapNotNull { g ->
                    if (g["hidden"] == true) return@mapNotNull null
                    val name = g["name"]?.toString() ?: return@mapNotNull null
                    val type = g["type"]?.toString().orEmpty()
                    val listed = (g["proxies"] as? List<*>).orEmpty().map { it.toString() }
                    val used =
                        if (g["include-all"] == true || g["include-all-providers"] == true)
                            providers.values.flatten()
                        else (g["use"] as? List<*>).orEmpty().flatMap { providers[it.toString()].orEmpty() }
                    val all =
                        if (g["include-all"] == true || g["include-all-proxies"] == true) inline
                        else emptyList()
                    val filter = g["filter"]?.toString()?.let { runCatching { Regex(it) }.getOrNull() }
                    val exclude =
                        g["exclude-filter"]?.toString()?.let { runCatching { Regex(it) }.getOrNull() }
                    val pulled =
                        (all + used)
                            .map { it.name }
                            .filter { n -> filter == null || filter.containsMatchIn(n) }
                            .filter { n -> exclude == null || !exclude.containsMatchIn(n) }
                    ClashGroup(
                        name = name,
                        type = type,
                        now = null,
                        members = (listed + pulled).distinct(),
                        selectable = type.equals("select", ignoreCase = true),
                    )
                }
            ClashTree(groups, nodes, live = false)
        }

    @Suppress("UNCHECKED_CAST")
    private fun nodesOf(mapping: Map<String, Any?>): List<ClashNode> =
        ClashYaml.proxies(mapping).mapNotNull { p ->
            val name = p["name"]?.toString() ?: return@mapNotNull null
            ClashNode(name, p["type"]?.toString().orEmpty(), p["server"]?.toString())
        }

    /** A provider's nodes from mihomo's cached copy (its `path`, or mihomo's default path). */
    private fun providerNodes(name: String, provider: Map<String, Any?>?): List<ClashNode> {
        provider ?: return emptyList()
        val path = provider["path"]?.toString()
        val url = provider["url"]?.toString()
        val candidates =
            listOfNotNull(
                path?.let { if (File(it).isAbsolute) File(it) else File(mihomoHome, it) },
                url?.let { File(mihomoHome, "proxies/" + md5(it)) },
                File(mihomoHome, "proxies/$name.yaml"),
            )
        val text = candidates.firstOrNull { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val mapping = text?.let { ClashYaml.parseMapping(it) } ?: return emptyList()
        return nodesOf(mapping)
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

private val BUILT_IN = setOf("direct", "reject", "rejectdrop", "pass", "compatible", "dns")

private val PROTOCOLS =
    mapOf(
        "ss" to "Shadowsocks",
        "shadowsocks" to "Shadowsocks",
        "ssr" to "ShadowsocksR",
        "shadowsocksr" to "ShadowsocksR",
        "vmess" to "VMess",
        "vless" to "VLESS",
        "trojan" to "Trojan",
        "hysteria" to "Hysteria",
        "hysteria2" to "Hysteria2",
        "tuic" to "TUIC",
        "wireguard" to "WireGuard",
        "socks5" to "SOCKS5",
        "http" to "HTTP",
        "snell" to "Snell",
        "anytls" to "AnyTLS",
        "ssh" to "SSH",
        "mieru" to "Mieru",
        "direct" to "Direct",
        "reject" to "Reject",
        "rejectdrop" to "Reject",
        "pass" to "Pass",
        "dns" to "DNS",
        "selector" to "Select",
        "select" to "Select",
        "urltest" to "URL test",
        "url-test" to "URL test",
        "fallback" to "Fallback",
        "loadbalance" to "Load balance",
        "load-balance" to "Load balance",
        "relay" to "Relay",
    )

fun protocolName(type: String): String = PROTOCOLS[type.lowercase()] ?: type
