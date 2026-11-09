package app.quartz.clash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import org.koin.compose.koinInject

/**
 * A tunnel's or node's last delay test result, for the right side of its row: the Tunnels list's
 * own latency label and colours (TunnelList.kt pingLatencyLabel/Color). Nothing until tested.
 */
@Composable
fun QuartzLatencyText(key: String, modifier: Modifier = Modifier, delays: QuartzDelays = koinInject()) {
    val state by delays.state.collectAsStateWithLifecycle()
    if (key !in state.results) return
    val ms = state.results[key]
    Text(
        text = ms?.let { "${it}ms" } ?: stringResource(R.string.ping_unreachable),
        style = MaterialTheme.typography.bodySmall,
        color = latencyColor(ms),
        modifier = modifier,
    )
}

private fun latencyColor(ms: Int?): Color =
    when {
        ms == null -> AlertRed
        ms <= 50 -> SilverTree
        ms <= 150 -> Straw
        else -> AlertRed
    }
