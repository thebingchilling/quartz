package com.follow.clash

import com.follow.clash.autotunnel.AppSettings
import com.follow.clash.autotunnel.AutoTunnelRules
import com.follow.clash.autotunnel.AutoTunnelSettings
import com.follow.clash.autotunnel.KillSwitch
import com.follow.clash.autotunnel.NetworkKind
import com.follow.clash.autotunnel.NetworkSnapshot
import com.follow.clash.autotunnel.SimTunnel
import com.follow.clash.autotunnel.TunnelTarget
import com.follow.clash.autotunnel.WifiTunnel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTunnelRulesTest {
    private val warp = TunnelTarget(profileId = 1)
    private val auckland = TunnelTarget(profileId = 2, group = "Windscribe", proxy = "NZ Auckland")
    private val windscribe = TunnelTarget(profileId = 3)
    private val settings = AutoTunnelSettings(
        enabled = true,
        defaultTunnel = warp,
        trustedNetworks = listOf("Home", "Office-*"),
        wifiTunnels = listOf(WifiTunnel("eduroam", windscribe)),
        mobileSims = listOf(1, 2),
        simTunnels = listOf(SimTunnel(2, auckland)),
        apps = listOf(
            AppSettings("app:nz.bank", "nz.bank", "nz.bank", target = auckland),
            AppSettings("app:nz.news", "nz.news", "nz.news"),
            AppSettings("app:torrent", "torrent", "torrent", killSwitch = true),
        ),
        killSwitchTunnels = listOf(1L),
    )

    private fun wifi(ssid: String?) = NetworkSnapshot(NetworkKind.WIFI, ssid)
    private fun sim(id: Int?) = NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = id)

    @Test
    fun `untrusted wifi connects, trusted wifi disconnects`() {
        val cafe = AutoTunnelRules.decide(settings, wifi("Cafe"))!!
        assertEquals(true, cafe.connect)
        assertEquals(warp, cafe.target)
        assertEquals(false, AutoTunnelRules.decide(settings, wifi("Home"))!!.connect)
        assertEquals(false, AutoTunnelRules.decide(settings, wifi("Office-3"))!!.connect)
        assertEquals(true, AutoTunnelRules.decide(settings, wifi(null))!!.connect)
        assertEquals(windscribe, AutoTunnelRules.decide(settings, wifi("eduroam"))!!.target)
        assertEquals(false, AutoTunnelRules.decide(settings.copy(tunnelOnWifi = false), wifi("Cafe"))!!.connect)
    }

    @Test
    fun `mobile data follows the chosen SIMs and their tunnels`() {
        assertEquals(auckland, AutoTunnelRules.decide(settings, sim(2))!!.target)
        assertEquals(warp, AutoTunnelRules.decide(settings, sim(1))!!.target)
        assertEquals(false, AutoTunnelRules.decide(settings, sim(3))!!.connect)
        assertEquals(true, AutoTunnelRules.decide(settings, sim(null))!!.connect)
        assertEquals(false, AutoTunnelRules.decide(settings.copy(tunnelOnMobileData = false), sim(1))!!.connect)
        assertEquals(windscribe, AutoTunnelRules.decide(settings.copy(mobileTunnel = windscribe), sim(1))!!.target)
    }

    @Test
    fun `no network leaves the tunnel unless told to stop`() {
        val none = NetworkSnapshot(NetworkKind.NONE)
        assertNull(AutoTunnelRules.decide(settings, none))
        assertEquals(false, AutoTunnelRules.decide(settings.copy(stopOnNoNetwork = true), none)!!.connect)
    }

    @Test
    fun `an open app wins over the network`() {
        val bank = AutoTunnelRules.decide(settings, wifi("Home"), listOf("nz.bank"))!!
        assertEquals("app:nz.bank", bank.key)
        assertEquals(auckland, bank.target)
        assertEquals(warp, AutoTunnelRules.decide(settings, wifi("Home"), listOf("nz.news"))!!.target)
    }

    @Test
    fun `the kill switch blocks only while no tunnel runs, and never on trusted wifi`() {
        assertEquals(true, AutoTunnelRules.blocksAll(settings, wifi("Cafe"), 1))
        assertEquals(false, AutoTunnelRules.blocksAll(settings, wifi("Home"), 1))
        assertNull(KillSwitch.decide(settings, RunState.STARTED, wifi("Cafe"), false, 1))
        assertEquals(emptyList<String>(), KillSwitch.decide(settings, RunState.STOPPED, wifi("Cafe"), false, 1))
        assertEquals(listOf("torrent"), KillSwitch.decide(settings, RunState.STOPPED, wifi("Cafe"), true, 1))
        assertEquals(listOf("torrent"), KillSwitch.decide(settings, RunState.STOPPED, wifi("Cafe"), false, 2))
        assertNull(KillSwitch.decide(AutoTunnelSettings(), RunState.STOPPED, wifi("Cafe"), false, null))
    }

    @Test
    fun `an app stays open until closed from recents`() {
        val apps = setOf("nz.bank")
        var recent = AutoTunnelRules.stepOpenApps(emptyList(), "nz.bank", emptySet(), apps)
        assertEquals(listOf("nz.bank"), recent)
        recent = AutoTunnelRules.stepOpenApps(recent, "launcher", setOf("nz.bank"), apps)
        assertEquals(listOf("nz.bank"), recent)
        recent = AutoTunnelRules.stepOpenApps(recent, "launcher", emptySet(), apps)
        assertEquals(emptyList<String>(), recent)
    }

    @Test
    fun `settings read the JSON Flutter writes`() {
        val json = """{"enabled":true,"defaultTunnel":{"profileId":5,"group":null,"proxy":null},
            |"trustedNetworks":["Home*"],"wifiTunnels":[{"ssid":"Cafe","target":{"profileId":6,"group":"G","proxy":"P"}}],
            |"tunnelOnMobileData":false,"mobileSims":[3],"mobileTunnel":null,
            |"simTunnels":[{"simId":3,"target":{"profileId":7,"group":null,"proxy":null}}],
            |"stopOnNoNetwork":true,"debounceSeconds":5,
            |"apps":[{"id":"app:x","app":"x","detected":"x","label":"X","target":null,"killSwitch":false}],
            |"killSwitchTunnels":[5],"currentProfileId":5,
            |"rendered":{"6/G/P":{"profileId":6,"path":"/x.yaml","label":"L","selectedMap":{"G":"P"}}}}"""
            .trimMargin()
        val parsed = Gson().fromJson(json, AutoTunnelSettings::class.java)
        assertEquals(5L, parsed.defaultTunnel?.profileId)
        assertEquals(true, parsed.tunnelOnWifi)
        assertEquals("6/G/P", parsed.wifiTunnels.single().target?.key)
        assertEquals(false, parsed.tunnelOnMobileData)
        assertEquals(listOf(3), parsed.mobileSims)
        assertEquals(7L, parsed.simTunnels.single().target?.profileId)
        assertEquals(5, parsed.debounceSeconds)
        assertNull(parsed.apps.single().target)
        assertEquals(listOf(5L), parsed.killSwitchTunnels)
        assertEquals(mapOf("G" to "P"), parsed.rendered["6/G/P"]?.selectedMap)
        assertEquals(2, Gson().fromJson("{}", AutoTunnelSettings::class.java).debounceSeconds)
    }
}
