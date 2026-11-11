package app.quartz.clash.ui

import app.quartz.bridge.EnginePlaceholder
import app.quartz.core.data.EngineProfile
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.util.network.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The Tunnels screen's delay test (FORK.md §5): everything at once, connected or not. Every
 * WireGuard/AmneziaWG tunnel's server gets one ping; every Clash node gets a URL test through
 * mihomo when its tunnel runs, otherwise one ping of its server. Results arrive as they come.
 * A value of null means unreachable; a missing key means untested.
 */
class QuartzDelays(
    private val tunnelRepository: TunnelRepository,
    private val networkUtils: NetworkUtils,
    private val trees: ClashTrees,
    private val openVpnProfiles: app.quartz.bridge.OpenVpnProfiles,
    private val scope: CoroutineScope,
) {
    data class State(val results: Map<String, Int?> = emptyMap(), val testing: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun testAll() {
        if (_state.value.testing) return
        _state.value = State(testing = true)
        scope.launch {
            val permits = Semaphore(PARALLEL)
            runCatching {
                coroutineScope {
                    tunnelRepository.getAll().forEach { tunnel ->
                        when (EnginePlaceholder.engineOf(tunnel.quickConfig)) {
                            null ->
                                launch {
                                    val host =
                                        runCatching { tunnel.getConfig().peers.firstOrNull()?.host }
                                            .getOrNull()
                                    val ms = host?.let { permits.withPermit { ping(it) } }
                                    put(tunnelKey(tunnel.id), ms)
                                }
                            EngineProfile.ENGINE_MIHOMO ->
                                launch {
                                    val tree = trees.load(tunnel.id) ?: return@launch
                                    val api = trees.api(tunnel.id)
                                    tree.testableNodes().forEach { node ->
                                        launch {
                                            val ms =
                                                permits.withPermit {
                                                    if (api != null)
                                                        runCatching {
                                                                api.delay(node.name, timeoutMs = URL_TIMEOUT_MS)
                                                            }
                                                            .getOrNull()
                                                    else node.server?.let { ping(it) }
                                                }
                                            put(nodeKey(tunnel.id, node.name), ms)
                                        }
                                    }
                                }
                            EngineProfile.ENGINE_OPENVPN ->
                                launch {
                                    // The profile's first server (`remote host [port]`).
                                    val host =
                                        openVpnProfiles.read(tunnel.id)?.first?.let {
                                            Regex("(?im)^\\s*remote\\s+(\\S+)")
                                                .find(it)
                                                ?.groupValues
                                                ?.get(1)
                                        }
                                    val ms = host?.let { permits.withPermit { ping(it) } }
                                    put(tunnelKey(tunnel.id), ms)
                                }
                            else -> Unit
                        }
                    }
                }
            }
            _state.update { it.copy(testing = false) }
        }
    }

    private suspend fun ping(host: String): Int? =
        runCatching {
                val stats = networkUtils.pingWithStats(host, 1, PING_TIMEOUT_MS)
                if (stats.isReachable) stats.rttAvg.toInt() else null
            }
            .getOrNull()

    private fun put(key: String, ms: Int?) = _state.update { it.copy(results = it.results + (key to ms)) }

    companion object {
        private const val PARALLEL = 48
        private const val PING_TIMEOUT_MS = 1_500L
        private const val URL_TIMEOUT_MS = 2_500

        fun tunnelKey(tunnelId: Int) = "t:$tunnelId"

        fun nodeKey(tunnelId: Int, node: String) = "n:$tunnelId:$node"
    }
}
