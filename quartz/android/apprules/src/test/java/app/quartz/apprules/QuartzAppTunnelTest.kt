package app.quartz.apprules

import com.wgtunnel.backend.autotunnel.AutoTunnelDecision
import com.wgtunnel.backend.autotunnel.AutoTunnelEngine
import com.wgtunnel.backend.autotunnel.AutoTunnelNetwork
import com.wgtunnel.backend.autotunnel.AutoTunnelNetworkType
import com.wgtunnel.backend.autotunnel.AutoTunnelPolicy
import com.wgtunnel.backend.autotunnel.AutoTunnelSnapshot
import com.wgtunnel.backend.autotunnel.AutoTunnelTunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class QuartzAppTunnelTest {
    private val home = AutoTunnelTunnel(id = 1, name = "Home", isPrimaryTunnel = true)
    private val work = AutoTunnelTunnel(id = 2, name = "Work")
    private val trustedWifi =
        AutoTunnelSnapshot(
            network =
                AutoTunnelNetwork(
                    type = AutoTunnelNetworkType.WIFI,
                    ssid = "Home",
                    hasUsableNetwork = true,
                ),
            policy = AutoTunnelPolicy(isTunnelOnWifiEnabled = true, trustedNetworkSsids = listOf("Home")),
            tunnels = listOf(home, work),
        )
    private val engine = AutoTunnelEngine()

    @Test
    fun `no rule app on screen leaves upstream's decision alone`() {
        assertSame(trustedWifi, QuartzAppTunnel.forApp(trustedWifi, null))
        assertEquals(
            AutoTunnelDecision.DoNothing,
            engine.evaluate(QuartzAppTunnel.forApp(trustedWifi, null)),
        )
    }

    @Test
    fun `a rule app starts its tunnel even on a trusted network`() {
        val decision = engine.evaluate(QuartzAppTunnel.forApp(trustedWifi, 2))
        assertEquals(AutoTunnelDecision.Sync(start = setOf(2L), stop = emptySet()), decision)
    }

    @Test
    fun `a rule app swaps out another running tunnel`() {
        val running = trustedWifi.copy(activeTunnelIds = setOf(1L))
        val decision = engine.evaluate(QuartzAppTunnel.forApp(running, 2).copy(activeTunnelIds = setOf(1L)))
        assertEquals(AutoTunnelDecision.Sync(start = setOf(2L), stop = setOf(1L)), decision)
    }

    @Test
    fun `nothing changes without a usable network or for a missing tunnel`() {
        val offline = trustedWifi.copy(network = trustedWifi.network.copy(hasUsableNetwork = false))
        assertSame(offline, QuartzAppTunnel.forApp(offline, 2))
        assertSame(trustedWifi, QuartzAppTunnel.forApp(trustedWifi, 99))
    }

    @Test
    fun `a captive portal auto-tunnel stays off on still wins`() {
        val portal =
            trustedWifi.copy(
                network = trustedWifi.network.copy(captivePortal = true),
                policy = trustedWifi.policy.copy(disableTunnelOnCaptivePortal = true),
            )
        assertSame(portal, QuartzAppTunnel.forApp(portal, 2))
    }

    @Test
    fun `rules match by package in any profile, and only when enabled`() {
        val rule =
            AppRule(
                id = "r",
                tunnelId = 2,
                apps = listOf(AppRef("com.example.mail", "Mail", AppProfile.WORK, 10)),
            )
        val data = AppRulesData(rules = listOf(rule))
        assertEquals(rule, data.ruleFor("com.example.mail"))
        assertNull(data.ruleFor("com.example.other"))
        assertNull(data.ruleFor(null))
        assertNull(data.copy(enabled = false).ruleFor("com.example.mail"))
    }
}
