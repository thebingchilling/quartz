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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.transformLatest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Rule apps by most recent use (newest first) still counted as open, and the tunnel wanted. */
data class AppTunnelState(val recent: List<String> = emptyList(), val tunnelId: Int? = null)

/**
 * Auto-tunnel by app (FORK.md §14). Wraps the snapshots upstream's AutoTunnelService feeds its
 * reconciler (one line): while an app with a rule is open, the snapshot is replaced by one in
 * which only that rule's tunnel qualifies, so upstream's own engine connects it (and stops any
 * other). When it's closed, the real snapshot comes back and upstream's rules (Wi-Fi, mobile data,
 * trusted networks) decide again. Expired tunnels never qualify ([app.quartz.core.QuartzAutoTunnel]
 * already filters them from the snapshot's tunnels).
 *
 * "Open" means: on screen, or (main-profile apps, with usage access) still in the recent apps,
 * i.e. not swiped away there or backed out of. Apps whose closing can't be seen (other profiles,
 * no usage access) count as open only while on screen. With several rule apps open, the most
 * recently used one wins.
 */
object QuartzAppTunnel : KoinComponent {
    private val context: Context by inject()
    private val rules: AppRules by inject()

    /** How long a tunnel stays after its app stops counting as open, so a quick switch doesn't flap. */
    val LINGER: Duration = 4.seconds

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun overlay(snapshots: Flow<AutoTunnelSnapshot>): Flow<AutoTunnelSnapshot> {
        val inMainProfile = mutableMapOf<String, Boolean>()
        val trackable = { pkg: String ->
            inMainProfile.getOrPut(pkg) {
                runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            }
        }
        var first = true
        val ruleTunnel =
            combine(ForegroundApp.activity(context), rules.data) { activity, data -> activity to data }
                .scan(AppTunnelState()) { state, (activity, data) -> step(state, activity, data, trackable) }
                .map { it.tunnelId }
                .distinctUntilChanged()
                .transformLatest { tunnelId ->
                    // Only losing the tunnel waits; the first value (auto-tunnel starting) doesn't.
                    if (tunnelId == null && !first) delay(LINGER)
                    first = false
                    emit(tunnelId)
                }
                .distinctUntilChanged()
        return combine(snapshots, ruleTunnel) { snapshot, tunnelId -> forApp(snapshot, tunnelId) }
            .distinctUntilChanged()
    }

    /**
     * The next [AppTunnelState]: a rule app on screen moves to the front; apps no longer open drop
     * out (closed from recents, or left, for apps whose closing can't be seen: [trackable] is
     * false for apps outside the main profile); the newest remaining one's rule picks the tunnel.
     */
    fun step(
        state: AppTunnelState,
        activity: AppActivity,
        data: AppRulesData,
        trackable: (String) -> Boolean,
    ): AppTunnelState {
        val fg = activity.foreground
        var recent = state.recent
        if (fg != null && data.ruleFor(fg) != null) recent = listOf(fg) + (recent - fg)
        val open = activity.open
        recent =
            recent.filter { pkg ->
                data.ruleFor(pkg) != null &&
                    (pkg == fg || (open != null && trackable(pkg) && pkg in open))
            }
        return AppTunnelState(recent, recent.firstOrNull()?.let { data.ruleFor(it)?.tunnelId })
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
