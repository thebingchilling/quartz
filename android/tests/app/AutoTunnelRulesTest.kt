package com.follow.clash

import com.follow.clash.autotunnel.AutoTunnelRule
import com.follow.clash.autotunnel.AutoTunnelRules
import com.follow.clash.autotunnel.AutoTunnelSettings
import com.follow.clash.autotunnel.KillSwitch
import com.follow.clash.autotunnel.NetworkKind
import com.follow.clash.autotunnel.NetworkMatch
import com.follow.clash.autotunnel.NetworkSnapshot
import com.follow.clash.autotunnel.RuleApp
import com.follow.clash.autotunnel.TunnelTarget
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTunnelRulesTest {
    private val warp = TunnelTarget(profileId = 1)
    private val windscribe = TunnelTarget(profileId = 2, group = "windscribe")
    private val bnz = RuleApp("nz.co.bnz", "BNZ", "nz.co.bnz")
    private val chrome = RuleApp("com.android.chrome", "Chrome", "com.android.chrome")
    private val settings = AutoTunnelSettings(
        enabled = true,
        defaultTunnel = warp,
        rules = listOf(
            AutoTunnelRule("eduroam", networks = listOf(NetworkMatch("wifi", ssid = "eduroam")), target = warp),
            AutoTunnelRule(
                "bank",
                networks = listOf(NetworkMatch("mobile", simId = 2)),
                apps = listOf(bnz),
                target = windscribe,
            ),
            AutoTunnelRule(
                "browsing",
                networks = listOf(NetworkMatch("wifi", ssid = "eduroam"), NetworkMatch("mobile")),
                apps = listOf(chrome),
            ),
        ),
    )

    private fun wifi(ssid: String?) = NetworkSnapshot(NetworkKind.WIFI, ssid)
    private fun sim(id: Int?) = NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = id)

    @Test
    fun `a network rule connects and anything else disconnects`() {
        assertEquals("rule:eduroam", AutoTunnelRules.decide(settings, wifi("eduroam"))!!.key)
        assertEquals(warp, AutoTunnelRules.decide(settings, wifi("eduroam"))!!.target)
        assertEquals("otherwise", AutoTunnelRules.decide(settings, wifi("Cafe"))!!.key)
        assertNull(AutoTunnelRules.decide(settings.copy(disconnectOtherwise = false), wifi("Cafe")))
    }

    @Test
    fun `a rule with apps needs one open and its network`() {
        assertEquals(false, AutoTunnelRules.decide(settings, sim(2))!!.connect)
        assertEquals(windscribe, AutoTunnelRules.decide(settings, sim(2), listOf("nz.co.bnz"))!!.target)
        assertEquals(false, AutoTunnelRules.decide(settings, sim(1), listOf("nz.co.bnz"))!!.connect)
        assertEquals(
            "rule:browsing",
            AutoTunnelRules.decide(settings, wifi("eduroam"), listOf("com.android.chrome"))!!.key,
        )
    }

    @Test
    fun `wildcards, any wifi and an empty network list`() {
        val any = AutoTunnelSettings(
            rules = listOf(
                AutoTunnelRule("home", networks = listOf(NetworkMatch("wifi", ssid = "Home*"))),
                AutoTunnelRule("wifi", networks = listOf(NetworkMatch("wifi"))),
                AutoTunnelRule("everywhere"),
            ),
        )
        assertEquals("rule:home", AutoTunnelRules.decide(any, wifi("Home-5G"))!!.key)
        assertEquals("rule:wifi", AutoTunnelRules.decide(any, wifi(null))!!.key)
        assertEquals("rule:everywhere", AutoTunnelRules.decide(any, sim(3))!!.key)
    }

    @Test
    fun `no network stops the tunnel by default`() {
        val none = NetworkSnapshot(NetworkKind.NONE)
        assertEquals(false, AutoTunnelRules.decide(settings, none)!!.connect)
        assertNull(AutoTunnelRules.decide(settings.copy(stopOnNoNetwork = false), none))
    }

    @Test
    fun `the kill switch blocks only while a tunnel is wanted and down`() {
        val on = settings.copy(killSwitch = true)
        assertNull(KillSwitch.decide(on, RunState.STARTED, false, true))
        assertEquals(emptyList<String>(), KillSwitch.decide(on, RunState.STOPPED, false, true))
        assertNull(KillSwitch.decide(on, RunState.STOPPED, true, true))
        assertNull(KillSwitch.decide(on, RunState.STOPPED, false, false))
        assertNull(KillSwitch.decide(settings, RunState.STOPPED, false, true))
    }

    @Test
    fun `an app stays open until closed from recents`() {
        val apps = setOf("nz.co.bnz")
        var recent = AutoTunnelRules.stepOpenApps(emptyList(), "nz.co.bnz", emptySet(), apps)
        assertEquals(listOf("nz.co.bnz"), recent)
        recent = AutoTunnelRules.stepOpenApps(recent, "launcher", setOf("nz.co.bnz"), apps)
        assertEquals(listOf("nz.co.bnz"), recent)
        recent = AutoTunnelRules.stepOpenApps(recent, "launcher", emptySet(), apps)
        assertEquals(emptyList<String>(), recent)
    }

    @Test
    fun `settings read the JSON Flutter writes`() {
        val json = """{"enabled":true,"rules":[{"id":"r1","enabled":true,
            |"networks":[{"kind":"mobile","ssid":null,"simId":2}],
            |"apps":[{"app":"nz.co.bnz","label":"BNZ","detected":"nz.co.bnz"}],
            |"connect":true,"target":{"profileId":6,"group":"G","proxy":"P"}}],
            |"defaultTunnel":null,"disconnectOtherwise":false,"stopOnNoNetwork":true,
            |"killSwitch":true,"debounceSeconds":5,"currentProfileId":5,
            |"rendered":{"6/G/P":{"profileId":6,"path":"/x.yaml","label":"L","selectedMap":{"G":"P"}}}}"""
            .trimMargin()
        val parsed = Gson().fromJson(json, AutoTunnelSettings::class.java)
        val rule = parsed.rules.single()
        assertEquals(2, rule.networks.single().simId)
        assertEquals("6/G/P", rule.target?.key)
        assertEquals(setOf("nz.co.bnz"), parsed.watchedApps)
        assertEquals(false, parsed.disconnectOtherwise)
        assertEquals(true, parsed.killSwitch)
        assertEquals(5, parsed.debounceSeconds)
        assertEquals(mapOf("G" to "P"), parsed.rendered["6/G/P"]?.selectedMap)
        val empty = Gson().fromJson("{}", AutoTunnelSettings::class.java)
        assertEquals(true, empty.stopOnNoNetwork)
        assertEquals(true, empty.disconnectOtherwise)
    }
}
