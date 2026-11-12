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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.koinInject

/**
 * The fork data every Tunnels list row shows, read once for the whole list: each row used to
 * watch both tables itself, so every database change re-ran a query per row (a laggy list).
 */
class QuartzRowData(metaDao: TunnelMetaDao, profileDao: EngineProfileDao, scope: CoroutineScope) {
    val expiries: StateFlow<Map<Int, Long>> =
        metaDao
            .flow()
            .map { metas -> metas.mapNotNull { m -> m.expiresAt?.let { m.tunnelId to it } }.toMap() }
            .catch { emit(emptyMap()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val subscriptions: StateFlow<Map<Int, EngineProfile>> =
        profileDao
            .flow()
            .map { list -> list.filter { it.subscriptionUrl != null }.associateBy { it.tunnelId } }
            .catch { emit(emptyMap()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/**
 * A tunnel row's description: its protocol (WireGuard, AmneziaWG, OpenVPN or Clash) always, then
 * upstream's own lines (entry tunnel), then WARP expiry as a days-left bar, and a
 * Clash subscription's traffic and expiry. Latency is on the row's right side
 * ([app.quartz.clash.ui.QuartzLatencyText]). Called from one line in upstream's TunnelList.kt.
 */
@Composable
fun quartzTunnelDescription(
    tunnelId: Int,
    upstream: (@Composable () -> Unit)?,
    quickConfig: String = "",
    data: QuartzRowData = koinInject(),
): (@Composable () -> Unit)? {
    val expiries by data.expiries.collectAsStateWithLifecycle()
    val subscriptions by data.subscriptions.collectAsStateWithLifecycle()
    val expiresAt = expiries[tunnelId]
    val engine = remember(quickConfig) { EnginePlaceholder.engineOf(quickConfig) }
    val subscription = if (engine == EngineProfile.ENGINE_MIHOMO) subscriptions[tunnelId] else null
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
