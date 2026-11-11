package app.quartz.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.bridge.EnginePlaceholder
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import app.quartz.core.data.TunnelMetaDao
import com.zaneschepke.wireguardautotunnel.R
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * A tunnel row's description: its protocol (WireGuard, AmneziaWG, OpenVPN or Clash) always, then
 * upstream's own lines (entry tunnel), then WARP expiry as a days-left bar with the date, and a
 * Clash subscription's traffic and expiry. Latency is on the row's right side
 * ([app.quartz.clash.ui.QuartzLatencyText]). Called from one line in upstream's TunnelList.kt.
 */
@Composable
fun quartzTunnelDescription(
    tunnelId: Int,
    upstream: (@Composable () -> Unit)?,
    quickConfig: String = "",
    metaDao: TunnelMetaDao = koinInject(),
    profileDao: EngineProfileDao = koinInject(),
): (@Composable () -> Unit)? {
    val expiresAt by
        remember(tunnelId) {
                metaDao.flow().map { metas ->
                    metas.firstOrNull { it.tunnelId == tunnelId }?.expiresAt
                }
            }
            .collectAsStateWithLifecycle(null)
    val engine = remember(quickConfig) { EnginePlaceholder.engineOf(quickConfig) }
    val subscription by
        remember(tunnelId, engine) {
                profileDao.flow().map { list ->
                    if (engine != EngineProfile.ENGINE_MIHOMO) null
                    else list.firstOrNull { it.tunnelId == tunnelId && it.subscriptionUrl != null }
                }
            }
            .collectAsStateWithLifecycle(null)
    val protocol =
        remember(quickConfig) {
            when {
                engine == EngineProfile.ENGINE_MIHOMO -> R.string.quartz_engine_clash
                engine == EngineProfile.ENGINE_OPENVPN -> R.string.quartz_engine_openvpn
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
            expiresAt?.let { QuartzExpiryBar(it, Modifier.padding(top = 2.dp)) }
            subscription?.let { QuartzSubscriptionSummary(it) }
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

/** Quartz shows no toasts (upstream's Toaster isn't drawn; MainActivity.kt). */
const val QUARTZ_TOASTS = false
