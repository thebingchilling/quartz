package app.quartz.clash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import org.koin.compose.koinInject

/**
 * A tunnel's or node's last delay test result, for the right side of its row: the Tunnels list's
 * own latency label and thresholds (TunnelList.kt pingLatencyLabel/Color), readable on light and
 * dark themes. Nothing until tested.
 */
@Composable
fun QuartzLatencyText(key: String, modifier: Modifier = Modifier, delays: QuartzDelays = koinInject()) {
    val state by delays.state.collectAsStateWithLifecycle()
    if (key !in state.results) return
    val ms = state.results[key]
    Text(
        text = ms?.let { "${it}ms" } ?: stringResource(R.string.ping_unreachable),
        style = MaterialTheme.typography.bodySmall,
        color = latencyColor(ms, MaterialTheme.colorScheme.surface.luminance() > 0.5f),
        modifier = modifier,
    )
}

/**
 * The tunnel list's latency colours on a dark theme; on a light one, darker shades of the same
 * green/yellow/red so they stay readable on a light background.
 */
private fun latencyColor(ms: Int?, light: Boolean): Color =
    when {
        ms == null || ms > 150 -> if (light) Color(0xFFB3261E) else AlertRed
        ms <= 50 -> if (light) Color(0xFF2E7D4F) else SilverTree
        else -> if (light) Color(0xFF8A6D00) else Straw
    }
