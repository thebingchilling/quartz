package app.quartz.clash.ui

import androidx.compose.runtime.Composable
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel
import org.koin.compose.koinInject

/**
 * The Tunnels top bar's delay test: upstream's server ping for every tunnel (which, as upstream,
 * waits until no tunnel is connected) plus a URL test of every node of a running Clash tunnel.
 */
@Composable
fun rememberQuartzDelayTest(
    viewModel: SharedAppViewModel,
    clashDelays: ClashDelays = koinInject(),
): () -> Unit = {
    viewModel.pingTunnels()
    clashDelays.testAll()
}
