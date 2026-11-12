package app.quartz.bridge

import app.quartz.engine.mihomo.MihomoApi
import app.quartz.engine.mihomo.MihomoProxies
import com.wgtunnel.backend.state.BackendStatus
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.state.BootstrapState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** A tunnel that started but can't carry traffic: it's stopped again and the start fails. */
class TunnelUnreachableException(message: String) : IllegalStateException(message)

/**
 * "Only connect what can connect": after an engine starts, these decide whether the tunnel
 * actually reaches its server, so a dead tunnel is stopped and the switch goes back off, instead
 * of leaving the phone on a VPN that carries nothing.
 */
internal object Reachability {

    private val BUILT_IN = setOf("direct", "reject", "rejectdrop", "pass", "compatible", "dns")

    /**
     * Clash: applies the user's saved picks, then URL-tests the main group's members through
     * mihomo. Fine when the picked member answers; when it doesn't but another node does, the
     * fastest one is picked instead (and saved); null error = connectable.
     */
    suspend fun clash(api: MihomoApi, tunnelId: Int, selections: ClashSelections): String? {
        val proxies = runCatching { api.proxies() }.getOrElse {
            return "mihomo isn't answering: ${it.message}"
        }
        applySaved(api, proxies, selections.get(tunnelId))
        val main =
            proxies.groups
                .filter { !it.hidden && it.name != MihomoApi.GLOBAL }
                .let { groups -> groups.firstOrNull { it.isSelectable } ?: groups.firstOrNull() }
                ?: return null // no proxy groups: nothing to choose, the rules decide
        repeat(ATTEMPTS) { attempt ->
            val results =
                runCatching { api.groupDelay(main.name, timeoutMs = CLASH_TIMEOUT_MS) }
                    .getOrDefault(emptyMap())
            val now = runCatching { api.proxies().byName[main.name]?.now }.getOrNull() ?: main.now
            val real =
                results.filterKeys { name ->
                    proxies.byName[name]?.type?.lowercase() !in BUILT_IN
                }
            if (now != null && now in results) return null
            if (now != null && proxies.byName[now]?.type?.lowercase() in BUILT_IN) return null
            val best = real.minByOrNull { it.value }?.key
            if (best != null) {
                if (main.isSelectable) {
                    runCatching { api.select(main.name, best) }
                    selections.set(tunnelId, main.name, best)
                    Timber.i("Clash: $now didn't answer, switched ${main.name} to $best")
                }
                return null
            }
            Timber.w("Clash: no node in ${main.name} answered (attempt ${attempt + 1})")
        }
        return "None of this profile's servers answered"
    }

    private suspend fun applySaved(
        api: MihomoApi,
        proxies: MihomoProxies,
        saved: Map<String, String>,
    ) {
        saved.forEach { (group, member) ->
            val g = proxies.byName[group] ?: return@forEach
            if (g.isSelectable && member in g.all && g.now != member)
                runCatching { api.select(group, member) }
        }
    }

    /**
     * WireGuard / AmneziaWG: waits for the first handshake. Connectable once the backend reports
     * the tunnel healthy; not when it's still failing its handshake (or couldn't resolve the
     * server) at the deadline, or went down. A tunnel the backend never reports on either way is
     * left running.
     */
    suspend fun wireGuard(status: StateFlow<BackendStatus>, tunnelId: Int): String? {
        var failing: String? = null
        var seen = false
        val settled =
            withTimeoutOrNull(WG_TIMEOUT_MS) {
                status.first { s ->
                    // The status can lag the start by a moment: missing only counts once seen.
                    val active = s.activeTunnels[tunnelId] ?: return@first seen
                    seen = true
                    val transport = active.transportState
                    failing =
                        when {
                            active.bootstrapState is BootstrapState.Failed ->
                                "Couldn't resolve the server's address"
                            transport is Tunnel.State.Up.HandshakeFailure ->
                                "The server didn't answer (no handshake)"
                            else -> null
                        }
                    transport is Tunnel.State.Up.Healthy || transport is Tunnel.State.Down
                }
            }
        if (settled != null) {
            val active = settled.activeTunnels[tunnelId]
            return if (active == null || active.transportState is Tunnel.State.Down)
                "The tunnel stopped while connecting"
            else null
        }
        return failing
    }

    private const val ATTEMPTS = 2
    private const val CLASH_TIMEOUT_MS = 5_000
    private const val WG_TIMEOUT_MS = 15_000L
}
