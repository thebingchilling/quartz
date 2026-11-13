package com.follow.clash

import com.follow.clash.autotunnel.AutoTunnelRules
import com.follow.clash.autotunnel.AutoTunnelSettings
import com.follow.clash.autotunnel.NetworkKind
import com.follow.clash.autotunnel.NetworkSnapshot
import com.follow.clash.autotunnel.WifiMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTunnelRulesTest {
    private val home = NetworkSnapshot(NetworkKind.WIFI, ssid = "Home")
    private val cafe = NetworkSnapshot(NetworkKind.WIFI, ssid = "Cafe")

    @Test
    fun `blacklisted wifi disconnects, any other connects`() {
        val settings = AutoTunnelSettings(enabled = true, ssids = listOf("Home"))
        assertEquals(false, AutoTunnelRules.decide(settings, home))
        assertEquals(true, AutoTunnelRules.decide(settings, cafe))
    }

    @Test
    fun `whitelisted wifi is the only one that connects`() {
        val settings =
            AutoTunnelSettings(enabled = true, wifiMode = WifiMode.WHITELIST, ssids = listOf("Cafe"))
        assertEquals(false, AutoTunnelRules.decide(settings, home))
        assertEquals(true, AutoTunnelRules.decide(settings, cafe))
    }

    @Test
    fun `mobile data connects only on the chosen SIM`() {
        val settings = AutoTunnelSettings(enabled = true, simIds = listOf(2))
        assertEquals(true, AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = 2)))
        assertEquals(false, AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = 1)))
    }

    @Test
    fun `a hidden wifi name and no network leave the tunnel alone`() {
        val settings = AutoTunnelSettings(enabled = true, ssids = listOf("Home"))
        assertEquals(null, AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.WIFI)))
        assertEquals(null, AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.NONE)))
    }
}
