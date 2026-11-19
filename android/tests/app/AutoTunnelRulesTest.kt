package com.follow.clash

import com.follow.clash.autotunnel.AutoTunnelRule
import com.follow.clash.autotunnel.AutoTunnelRules
import com.follow.clash.autotunnel.AutoTunnelSettings
import com.follow.clash.autotunnel.NetworkKind
import com.follow.clash.autotunnel.NetworkMatch
import com.follow.clash.autotunnel.NetworkSnapshot
import com.follow.clash.autotunnel.RuleApp
import com.follow.clash.autotunnel.RuleCategory
import com.follow.clash.autotunnel.TunnelTarget
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTunnelRulesTest {
    private val vless = TunnelTarget(profileId = 1)
    private val warp = TunnelTarget(profileId = 2)
    private val chinaIp = TunnelTarget(profileId = 3, group = "PROXY", proxy = "CN")
    private val discord = RuleApp("com.discord", "Discord", "com.discord")
    private val netflix = RuleApp("com.netflix", "Netflix", "com.netflix")
    private val data = AutoTunnelRule("data", networks = listOf(NetworkMatch("mobile")), target = vless)
    private val cafe = AutoTunnelRule(
        "cafe",
        networks = listOf(NetworkMatch("wifi", ssid = "Cafe")),
        apps = listOf(netflix),
        target = warp,
    )
    private val china = RuleCategory("china", "China", enabled = false)
    private val settings = AutoTunnelSettings(
        enabled = true,
        categories = listOf(china),
        rules = listOf(
            AutoTunnelRule(
                "china-data",
                categoryId = "china",
                networks = listOf(NetworkMatch("mobile")),
                target = chinaIp,
            ),
            AutoTunnelRule("home", networks = listOf(NetworkMatch("wifi", ssid = "MyHome")), connect = false),
            data,
            AutoTunnelRule("discord", apps = listOf(discord), target = warp),
            cafe,
        ),
    )

    private fun wifi(ssid: String?) = NetworkSnapshot(NetworkKind.WIFI, ssid)
    private fun sim(id: Int?) = NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = id)
    private val none = NetworkSnapshot(NetworkKind.NONE)

    private fun with(rule: AutoTunnelRule) =
        settings.copy(rules = settings.rules.map { if (it.id == rule.id) rule else it })

    @Test
    fun `a network rule connects its own tunnel or disconnects`() {
        val home = AutoTunnelRules.decide(settings, wifi("MyHome"))!!
        assertEquals(false to "home", home.connect to home.ruleId)
        val onData = AutoTunnelRules.decide(settings, sim(1), active = "home")!!
        assertEquals(vless, onData.target)
        assertEquals("data", onData.ruleId)
    }

    @Test
    fun `an app rule takes over and the network rule comes back after`() {
        val app = AutoTunnelRules.decide(settings, sim(1), listOf("com.discord"), "data")!!
        assertEquals(warp, app.target)
        assertEquals("data", AutoTunnelRules.decide(settings, sim(1), active = "discord")!!.ruleId)
    }

    @Test
    fun `a rule that ends disconnects and no rule in effect changes nothing`() {
        val ended = AutoTunnelRules.decide(settings, wifi("Elsewhere"), active = "data")!!
        assertEquals("ended", ended.key)
        assertNull(ended.ruleId)
        assertNull(AutoTunnelRules.decide(settings, wifi("Elsewhere")))
    }

    @Test
    fun `a rule that ends can leave the tunnel or connect another`() {
        val kept = AutoTunnelRules.decide(with(data.copy(onEnd = "keep")), wifi("x"), active = "data")!!
        assertNull(kept.connect)
        val hop = with(data.copy(onEnd = "connect", endTarget = warp))
        val hopped = AutoTunnelRules.decide(hop, wifi("x"), active = "data")!!
        assertEquals(true, hopped.connect)
        assertEquals(warp, hopped.target)
        assertEquals("home", AutoTunnelRules.decide(hop, wifi("MyHome"), active = "data")!!.ruleId)
    }

    @Test
    fun `AND needs both and OR either`() {
        assertEquals("cafe", AutoTunnelRules.decide(settings, wifi("Cafe"), listOf("com.netflix"))!!.ruleId)
        assertNull(AutoTunnelRules.decide(settings, wifi("Cafe")))
        val either = with(cafe.copy(matchAll = false))
        assertEquals("cafe", AutoTunnelRules.decide(either, wifi("Cafe"))!!.ruleId)
        assertEquals("cafe", AutoTunnelRules.decide(either, wifi("x"), listOf("com.netflix"))!!.ruleId)
    }

    @Test
    fun `a category turns its rules on together`() {
        val abroad = settings.copy(categories = listOf(china.copy(enabled = true)))
        assertEquals(chinaIp, AutoTunnelRules.decide(abroad, sim(1))!!.target)
        assertEquals("data", AutoTunnelRules.decide(settings, sim(1))!!.ruleId)
    }

    @Test
    fun `no network stops the tunnel unless the rule has a kill switch`() {
        assertEquals("none", AutoTunnelRules.decide(settings, none, active = "data")!!.key)
        assertNull(AutoTunnelRules.decide(with(data.copy(killSwitch = true)), none, active = "data"))
    }

    @Test
    fun `the kill switch blocks while its rule is in effect and down`() {
        val guarded = with(data.copy(killSwitch = true))
        assertTrue(AutoTunnelRules.blocksAll(guarded, "data", running = false))
        assertFalse(AutoTunnelRules.blocksAll(guarded, "data", running = true))
        assertFalse(AutoTunnelRules.blocksAll(guarded, "home", running = false))
        assertFalse(AutoTunnelRules.blocksAll(settings, "data", running = false))
        val abroad = settings.copy(categories = listOf(china.copy(enabled = true, killSwitch = true)))
        assertTrue(AutoTunnelRules.blocksAll(abroad, "china-data", running = false))
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
        val json = """{"enabled":true,"rules":[{"id":"r1","name":"Bank","enabled":true,
            |"categoryId":"c","networks":[{"kind":"mobile","ssid":null,"simId":2}],
            |"apps":[{"app":"nz.co.bnz","label":"BNZ","detected":"nz.co.bnz"}],"matchAll":false,
            |"connect":true,"target":{"profileId":6,"group":"G","proxy":"P"},"killSwitch":false,
            |"onEnd":"connect","endTarget":{"profileId":7}}],
            |"categories":[{"id":"c","name":"Trip","enabled":true,"killSwitch":true}],
            |"currentProfileId":5,
            |"rendered":{"6/G/P":{"profileId":6,"path":"/x.yaml","label":"L","selectedMap":{"G":"P"}}}}"""
            .trimMargin()
        val parsed = Gson().fromJson(json, AutoTunnelSettings::class.java)
        val rule = parsed.rules.single()
        assertEquals(2, rule.networks.single().simId)
        assertEquals("6/G/P", rule.target?.key)
        assertEquals(false, rule.matchAll)
        assertEquals("connect" to 7L, rule.onEnd to rule.endTarget?.profileId)
        assertTrue(parsed.killSwitchOf(rule))
        assertEquals(setOf("nz.co.bnz"), parsed.watchedApps)
        assertEquals(mapOf("G" to "P"), parsed.rendered["6/G/P"]?.selectedMap)
        val empty = Gson().fromJson("{}", AutoTunnelSettings::class.java)
        assertEquals(false, empty.enabled)
    }
}
