package app.quartz.bridge

import app.quartz.core.TunnelExpiredException
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.TunnelMetaDao
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.event.TunnelEvent
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.model.dns.TunnelDnsConfig
import com.wgtunnel.backend.state.ActiveTunnel
import com.wgtunnel.backend.state.BackendStatus
import com.wgtunnel.parser.Config
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.domain.model.LockdownSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EngineModeException(message: String) : IllegalStateException(message)

/**
 * Quartz's [TunnelProvider] (FORK.md §3), bound over upstream's through Koin. Every start goes
 * through here, so this is where tunnels are routed by ID: WireGuard/AmneziaWG to upstream's
 * backend unchanged, Clash profiles to mihomo. One engine runs at a time (Android allows one VPN),
 * and expired tunnels are refused. Upstream sees a single merged [backendStatus].
 */
class MultiEngineTunnelProvider(
    private val wireGuard: TunnelProvider,
    private val metaDao: TunnelMetaDao,
    private val profiles: ClashProfiles,
    private val mihomo: MihomoRunner,
    scope: CoroutineScope,
) : TunnelProvider {

    /** The Clash tunnel mihomo is running, as upstream's UI and notification expect to see it. */
    private val mihomoActive = MutableStateFlow<Pair<Int, ActiveTunnel>?>(null)

    override val backendStatus: StateFlow<BackendStatus> =
        combine(wireGuard.backendStatus, mihomoActive) { status, active ->
                if (active == null) status
                else BackendStatus(status.killSwitch, status.activeTunnels + active, status.dnsMode)
            }
            .stateIn(scope, SharingStarted.Eagerly, wireGuard.backendStatus.value)

    override val events: Flow<TunnelEvent> = wireGuard.events

    init {
        // mihomo can stop on its own (VPN revoked, another VPN started): drop it from the status.
        scope.launch {
            mihomo.state.collect { state ->
                val active = mihomoActive.value ?: return@collect
                if (state !is MihomoState.Running || state.tunnelId != active.first)
                    mihomoActive.value = null
            }
        }
    }

    override suspend fun startTunnel(
        tunnel: Tunnel,
        mode: BackendMode,
        tunnelDnsConfig: TunnelDnsConfig?,
        outerConfig: Config?,
    ): Result<Unit> {
        if (metaDao.get(tunnel.id)?.isExpired() == true) {
            return Result.failure(TunnelExpiredException(tunnel.name))
        }
        val profile = profiles.get(tunnel.id)
        if (profile == null) {
            if (mihomoActive.value != null) stopMihomo()
            return wireGuard.startTunnel(tunnel, mode, tunnelDnsConfig, outerConfig)
        }
        if (profile.engine != EngineProfile.ENGINE_MIHOMO) {
            return Result.failure(
                EngineModeException("${profile.engine} tunnels aren't supported yet")
            )
        }
        if (mode !is BackendMode.Vpn) {
            return Result.failure(EngineModeException("Clash tunnels run in VPN mode only for now"))
        }
        if (wireGuard.backendStatus.value.activeTunnels.isNotEmpty()) wireGuard.stopActiveTunnels()
        return mihomo.start(tunnel.id, tunnel.name, profiles.file(profile)).map { _ ->
            mihomoActive.value =
                tunnel.id to
                    ActiveTunnel(
                        tunnel = tunnel,
                        mode = mode,
                        transportState = Tunnel.State.Up.Healthy,
                        uptime = System.currentTimeMillis(),
                    )
        }
    }

    override suspend fun stopTunnel(tunnelId: Int): Result<Unit> =
        if (mihomoActive.value?.first == tunnelId) {
            stopMihomo()
            Result.success(Unit)
        } else wireGuard.stopTunnel(tunnelId)

    override suspend fun stopActiveTunnels(): Result<Unit> {
        if (mihomoActive.value != null) stopMihomo()
        return wireGuard.stopActiveTunnels()
    }

    override suspend fun setLockDown(settings: LockdownSettings): Result<Unit> =
        wireGuard.setLockDown(settings)

    override suspend fun disableLockDown(): Result<Unit> = wireGuard.disableLockDown()

    override suspend fun updateTunnel(tunnel: Tunnel): Result<Unit> =
        if (mihomoActive.value?.first == tunnel.id) Result.success(Unit)
        else wireGuard.updateTunnel(tunnel)

    private suspend fun stopMihomo() {
        mihomo.stop()
        mihomoActive.value = null
    }
}
