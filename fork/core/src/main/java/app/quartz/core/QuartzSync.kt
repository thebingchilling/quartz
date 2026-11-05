package app.quartz.core

import app.quartz.bridge.ClashProfiles
import app.quartz.core.data.TunnelMetaDao
import app.quartz.warp.WarpExpiry
import app.quartz.warp.WarpRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

/** Background work started with the app: keeps fork data in step with upstream's tunnels. */
class QuartzSync(
    scope: CoroutineScope,
    tunnelRepository: TunnelRepository,
    metaDao: TunnelMetaDao,
    warpRepository: WarpRepository,
    warpExpiry: WarpExpiry,
    clashProfiles: ClashProfiles,
) {
    init {
        scope.launch {
            tunnelRepository.userTunnelsFlow.collect { tunnels ->
                runCatching { clashProfiles.removeOrphans(tunnels.map { it.id }.toSet()) }
                    .onFailure { Timber.e(it, "Engine profile cleanup failed") }
                runCatching { warpRepository.sync(tunnels) }
                    .onFailure { Timber.e(it, "WARP sync failed") }
            }
        }
        scope.launch {
            runCatching { warpExpiry.check() }.onFailure { Timber.e(it, "Expiry check failed") }
            metaDao.flow().drop(1).collect { warpExpiry.schedule(it) }
        }
    }
}
