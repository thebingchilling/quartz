package app.quartz.apprules

import android.content.Context
import com.wgtunnel.backend.autotunnel.AutoTunnelNetwork
import com.wgtunnel.backend.autotunnel.AutoTunnelNetworkType
import com.wgtunnel.backend.autotunnel.AutoTunnelSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Auto-tunnel by app (FORK.md §14). Wraps the snapshots upstream's AutoTunnelService feeds its
 * reconciler (one line): while an app with a rule is on screen, the snapshot is replaced by one in
 * which only that rule's tunnel qualifies, so upstream's own engine connects it (and stops any
 * other). Leaving the app restores the real snapshot and upstream's rules (Wi-Fi, mobile data,
 * trusted networks) decide again. Expired tunnels never qualify ([app.quartz.core.QuartzAutoTunnel]
 * already filters them from the snapshot's tunnels).
 */
object QuartzAppTunnel : KoinComponent {
    private val context: Context by inject()
    private val rules: AppRules by inject()

    /** How long after leaving a rule's app its tunnel stays, so a share sheet or a quick switch doesn't flap. */
    val LINGER: Duration = 4.seconds

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun overlay(snapshots: Flow<AutoTunnelSnapshot>): Flow<AutoTunnelSnapshot> {
        var first = true
        val ruleTunnel =
            combine(ForegroundApp.current(context), rules.data) { app, data ->
                    data.ruleFor(app)?.tunnelId
                }
                .distinctUntilChanged()
                .transformLatest { tunnelId ->
                    // Only leaving a rule's app waits; the first value (auto-tunnel starting) doesn't.
                    if (tunnelId == null && !first) delay(LINGER)
                    first = false
                    emit(tunnelId)
                }
                .distinctUntilChanged()
        return combine(snapshots, ruleTunnel) { snapshot, tunnelId -> forApp(snapshot, tunnelId) }
            .distinctUntilChanged()
    }

    /**
     * [snapshot] as auto-tunnel should see it while an app whose rule picks [tunnelId] is on
     * screen. Unchanged when there's no rule, the tunnel isn't a candidate (deleted, expired), there
     * is no usable network, or the Wi-Fi is a captive portal auto-tunnel is told to stay off on.
     */
    fun forApp(snapshot: AutoTunnelSnapshot, tunnelId: Int?): AutoTunnelSnapshot {
        if (tunnelId == null) return snapshot
        val tunnel = snapshot.tunnels.firstOrNull { it.id == tunnelId.toLong() } ?: return snapshot
        val network = snapshot.network
        if (!network.hasUsableNetwork) return snapshot
        if (
            network.type == AutoTunnelNetworkType.WIFI &&
                network.captivePortal &&
                snapshot.policy.disableTunnelOnCaptivePortal
        )
            return snapshot
        // The engine's simplest rule that picks exactly one given tunnel: "on Ethernet, use the
        // Ethernet tunnel". The network key changes too, which clears a manual override, as any
        // network change does; leaving the app changes it back.
        return snapshot.copy(
            network =
                AutoTunnelNetwork(
                    type = AutoTunnelNetworkType.ETHERNET,
                    hasUsableNetwork = true,
                    captivePortal = false,
                ),
            policy = snapshot.policy.copy(isTunnelOnEthernetEnabled = true),
            tunnels = listOf(tunnel.copy(isEthernetTunnel = true)),
        )
    }
}
