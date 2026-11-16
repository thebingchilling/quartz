package com.follow.clash

import com.follow.clash.autotunnel.AppSettings
import com.follow.clash.autotunnel.AutoTunnelRules
import com.follow.clash.autotunnel.AutoTunnelSettings
import com.follow.clash.autotunnel.ConnectionKind
import com.follow.clash.autotunnel.ConnectionSettings
import com.follow.clash.autotunnel.KillSwitch
import com.follow.clash.autotunnel.NetworkKind
import com.follow.clash.autotunnel.NetworkSnapshot
import com.follow.clash.autotunnel.TunnelTarget
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTunnelRulesTest {
    private val warp = TunnelTarget(profileId = 1)
    private val auckland = TunnelTarget(profileId = 2, group = "Windscribe", proxy = "NZ Auckland")
    private val settings = AutoTunnelSettings(
        enabled = true,
        connections = listOf(
            ConnectionSettings("wifi:eduroam", ConnectionKind.WIFI, ssid = "eduroam", target = warp),
            ConnectionSettings("wifi:*", ConnectionKind.WIFI, killSwitch = true),
            ConnectionSettings("mobile:2", ConnectionKind.MOBILE, simId = 2, target = auckland),
        ),
        apps = listOf(
            AppSettings("app:nz.bank", "nz.bank", "nz.bank", target = auckland),
            AppSettings("app:torrent", "torrent", "torrent", killSwitch = true),
        ),
        killSwitchTunnels = listOf(1L),
    )
    private val eduroam = NetworkSnapshot(NetworkKind.WIFI, "eduroam")
    private val cafe = NetworkSnapshot(NetworkKind.WIFI, "Cafe")

    @Test
    fun `each connection picks its own tunnel`() {
        assertEquals("wifi:eduroam", AutoTunnelRules.decide(settings, eduroam)?.key)
        assertEquals(
            auckland,
            AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = 2))
                ?.target,
        )
        assertNull(AutoTunnelRules.decide(settings, cafe))
        assertNull(AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = 1)))
    }

    @Test
    fun `an open app wins over the connection`() {
        assertEquals("app:nz.bank", AutoTunnelRules.decide(settings, eduroam, listOf("nz.bank"))?.key)
    }

    @Test
    fun `named wifi falls back to the every-other entry`() {
        assertEquals("wifi:*", AutoTunnelRules.connectionFor(settings, cafe)?.id)
        assertEquals(true, AutoTunnelRules.blocksAll(settings, cafe, null))
        assertEquals(false, AutoTunnelRules.blocksAll(settings, eduroam, 2))
        assertEquals(true, AutoTunnelRules.blocksAll(settings, eduroam, 1))
    }

    @Test
    fun `the kill switch blocks only while no tunnel runs`() {
        assertNull(KillSwitch.decide(settings, RunState.STARTED, cafe, false, null))
        assertEquals(emptyList<String>(), KillSwitch.decide(settings, RunState.STOPPED, cafe, false, null))
        assertEquals(listOf("torrent"), KillSwitch.decide(settings, RunState.STOPPED, cafe, true, null))
        assertEquals(listOf("torrent"), KillSwitch.decide(settings, RunState.STOPPED, eduroam, false, 2))
        assertNull(KillSwitch.decide(AutoTunnelSettings(), RunState.STOPPED, cafe, false, null))
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
        val json = """{"enabled":true,"connections":[{"id":"mobile:3","kind":"mobile","ssid":null,
            |"simId":3,"target":{"profileId":5,"group":"G","proxy":"P"},"killSwitch":true}],
            |"apps":[{"id":"app:x","app":"x","detected":"x","label":"X","target":null,"killSwitch":false}],
            |"killSwitchTunnels":[5],"currentProfileId":5,
            |"rendered":{"mobile:3":{"profileId":5,"path":"/x.yaml","label":"L","selectedMap":{"G":"P"}}}}"""
            .trimMargin()
        val parsed = Gson().fromJson(json, AutoTunnelSettings::class.java)
        assertEquals(ConnectionKind.MOBILE, parsed.connections.single().kind)
        assertEquals(5L, parsed.connections.single().target?.profileId)
        assertNull(parsed.apps.single().target)
        assertEquals(listOf(5L), parsed.killSwitchTunnels)
        assertEquals(mapOf("G" to "P"), parsed.rendered["mobile:3"]?.selectedMap)
    }
}
