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
 * Adds Quartz's badges under a tunnel row's upstream description: the engine for non-WireGuard
 * tunnels ("Clash") and WARP expiry ("expires 5d" / "expired"). Called from one line in upstream's
 * TunnelList.kt.
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
    val engine = remember(quickConfig) { EnginePlaceholder.engineOf(quickConfig) }
    if (expiresAt == null && engine == null) return upstream
    return {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (engine == EngineProfile.ENGINE_MIHOMO) {
                Text(
                    stringResource(R.string.quartz_engine_clash),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            upstream?.invoke()
            expiresAt?.let { ExpiryBadge(it) }
        }
    }
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
