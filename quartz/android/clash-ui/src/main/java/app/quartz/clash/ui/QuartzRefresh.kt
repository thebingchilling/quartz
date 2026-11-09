package app.quartz.clash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.bridge.EnginePlaceholder
import app.quartz.bridge.Subscriptions
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import app.quartz.core.ip.IpLookup
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Refreshes what comes from online: every Clash subscription is downloaded again (applies on the
 * next connect), every Clash tunnel's groups and nodes are reloaded, and the connected tunnel's
 * exit IP is looked up again. Run by pull-to-refresh and the refresh button on the Tunnels screen.
 */
class QuartzRefresh(
    private val profileDao: EngineProfileDao,
    private val subscriptions: Subscriptions,
    private val trees: ClashTrees,
    private val tunnelRepository: TunnelRepository,
    private val ipLookup: IpLookup,
    private val scope: CoroutineScope,
) {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            runCatching {
                coroutineScope {
                    profileDao.flow().first().filter { it.subscriptionUrl != null }.forEach {
                        launch { subscriptions.update(it.tunnelId) }
                    }
                }
                tunnelRepository
                    .getAll()
                    .filter {
                        EnginePlaceholder.engineOf(it.quickConfig) == EngineProfile.ENGINE_MIHOMO
                    }
                    .forEach { trees.load(it.id) }
                ipLookup.invalidate()
            }
            _refreshing.value = false
        }
    }
}

/** Pull down on the Tunnels list to [QuartzRefresh.refresh]. Wraps the list in TunnelList.kt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuartzPullToRefresh(
    refresh: QuartzRefresh = koinInject(),
    content: @Composable () -> Unit,
) {
    val refreshing by refresh.refreshing.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh::refresh, modifier = Modifier.fillMaxSize()) {
        Box { content() }
    }
}

/** The Tunnels top bar's refresh button action. */
@Composable
fun rememberQuartzRefresh(refresh: QuartzRefresh = koinInject()): () -> Unit = refresh::refresh
