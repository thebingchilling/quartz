package app.quartz.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.core.ip.IpLookup
import app.quartz.core.ip.IpLookupState
import com.wgtunnel.backend.state.ActiveTunnel
import com.zaneschepke.wireguardautotunnel.R
import org.koin.compose.koinInject

/**
 * The connected tunnel's exit: IP with the country's flag, city/region/country and ISP, in place
 * of upstream's per-peer details under a running tunnel (same text style as the status and uptime
 * lines above it). Called from one line in upstream's TunnelStatisticsRow.kt.
 */
@Composable
fun QuartzIpInfo(activeTunnel: ActiveTunnel, lookup: IpLookup = koinInject()) {
    val results by lookup.results.collectAsStateWithLifecycle()
    val generation by lookup.generation.collectAsStateWithLifecycle()
    val healthy = activeTunnel.isTransportHealthy()
    val key = "${activeTunnel.tunnel?.id}:${activeTunnel.uptime}:$generation"
    LaunchedEffect(key, healthy) { if (healthy) lookup.ensure(key) }

    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.outline
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val state = results[key]) {
            is IpLookupState.Found -> {
                val info = state.info
                Text(
                    text = listOfNotNull(info.flag, info.ip).joinToString(" "),
                    style = style,
                    color = color,
                )
                if (info.place.isNotEmpty()) Text(text = info.place, style = style, color = color)
                info.isp?.let {
                    Text(text = stringResource(R.string.quartz_ip_isp, it), style = style, color = color)
                }
            }
            IpLookupState.Failed ->
                Text(
                    text = stringResource(R.string.quartz_ip_failed),
                    style = style,
                    color = color,
                    modifier = Modifier.clickable { lookup.ensure(key, retry = true) },
                )
            IpLookupState.Loading,
            null ->
                if (healthy)
                    Text(text = stringResource(R.string.quartz_ip_checking), style = style, color = color)
        }
    }
}
