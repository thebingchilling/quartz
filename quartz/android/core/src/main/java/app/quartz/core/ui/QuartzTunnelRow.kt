package app.quartz.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.bridge.EnginePlaceholder
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.TunnelMetaDao
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * A tunnel row's description: its protocol (WireGuard, AmneziaWG or Clash) always, then
 * upstream's own lines (entry tunnel), then WARP expiry. Latency is on the row's right side
 * ([app.quartz.clash.ui.QuartzLatencyText]). Called from one line in upstream's TunnelList.kt.
 */
@Composable
fun quartzTunnelDescription(
    tunnelId: Int,
    upstream: (@Composable () -> Unit)?,
    quickConfig: String = "",
    metaDao: TunnelMetaDao = koinInject(),
): (@Composable () -> Unit)? {
    val expiresAt by
        remember(tunnelId) {
                metaDao.flow().map { metas ->
                    metas.firstOrNull { it.tunnelId == tunnelId }?.expiresAt
                }
            }
            .collectAsStateWithLifecycle(null)
    val protocol =
        remember(quickConfig) {
            when {
                EnginePlaceholder.engineOf(quickConfig) == EngineProfile.ENGINE_MIHOMO ->
                    R.string.quartz_engine_clash
                isAmnezia(quickConfig) -> R.string.quartz_warp_awg
                else -> R.string.quartz_warp_wg
            }
        }
    return {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(protocol),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            upstream?.invoke()
            expiresAt?.let { ExpiryBadge(it) }
        }
    }
}

private val AMNEZIA_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1")

/** An AmneziaWG config sets any of its obfuscation fields to something other than 0. */
private fun isAmnezia(quickConfig: String): Boolean =
    quickConfig.lineSequence().any { line ->
        val key = line.substringBefore('=', "").trim().lowercase()
        val value = line.substringAfter('=', "").trim()
        key in AMNEZIA_KEYS && value.isNotEmpty() && value != "0"
    }

/** Where tapping a tunnel row goes: Clash tunnels open the Clash screen; null means upstream's. */
fun quartzTunnelRoute(tunnelId: Int, tunnels: List<TunnelConfig>): Route? {
    val tunnel = tunnels.firstOrNull { it.id == tunnelId } ?: return null
    return when (EnginePlaceholder.engineOf(tunnel.quickConfig)) {
        EngineProfile.ENGINE_MIHOMO -> Route.Clash(tunnelId)
        else -> null
    }
}

@Composable
private fun ExpiryBadge(expiresAt: Long) {
    val remaining = expiresAt - System.currentTimeMillis()
    val (text, color) =
        when {
            remaining <= 0 -> stringResource(R.string.quartz_expired) to AlertRed
            remaining < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(remaining).coerceAtLeast(1).toInt()
                pluralStringResource(R.plurals.quartz_expires_hours, hours, hours) to Straw
            }
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(remaining).toInt()
                pluralStringResource(R.plurals.quartz_expires_days, days, days) to
                    MaterialTheme.colorScheme.outline
            }
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Quartz shows no toasts (upstream's Toaster isn't drawn; MainActivity.kt). */
const val QUARTZ_TOASTS = false
