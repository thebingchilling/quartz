package app.quartz.core

import app.quartz.core.data.TunnelMetaDao
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Auto-tunnel's candidate list without expired tunnels, so its rules pick another tunnel rather
 * than trying one the expiry guard will refuse. Wraps the flow from one line in upstream's
 * AutoTunnelService. The list updates when [app.quartz.warp.WarpExpiry] marks a tunnel expired.
 */
object QuartzAutoTunnel : KoinComponent {
    private val metaDao: TunnelMetaDao by inject()

    fun candidates(tunnels: Flow<List<TunnelConfig>>): Flow<List<TunnelConfig>> =
        combine(tunnels, metaDao.flow()) { list, metas ->
            val expired = metas.filter { it.isExpired() }.map { it.tunnelId }.toSet()
            if (expired.isEmpty()) list else list.filterNot { it.id in expired }
        }
}
