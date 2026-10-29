package app.quartz.core

import app.quartz.core.data.TunnelMetaDao
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.model.dns.TunnelDnsConfig
import com.wgtunnel.parser.Config
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider

class TunnelExpiredException(name: String) : IllegalStateException("$name has expired")

/**
 * Wraps upstream's [TunnelProvider] and refuses to start expired tunnels. Every start goes through
 * the provider (manual, tile, shortcut, auto-tunnel), so this one check covers them all (FORK.md
 * §9, expiry). MultiEngineTunnelProvider takes this over in step 4.
 */
class ExpiryGuardTunnelProvider(
    private val delegate: TunnelProvider,
    private val metaDao: TunnelMetaDao,
) : TunnelProvider by delegate {

    override suspend fun startTunnel(
        tunnel: Tunnel,
        mode: BackendMode,
        tunnelDnsConfig: TunnelDnsConfig?,
        outerConfig: Config?,
    ): Result<Unit> {
        if (metaDao.get(tunnel.id)?.isExpired() == true) {
            return Result.failure(TunnelExpiredException(tunnel.name))
        }
        return delegate.startTunnel(tunnel, mode, tunnelDnsConfig, outerConfig)
    }
}
