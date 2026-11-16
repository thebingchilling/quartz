package com.follow.clash

import com.follow.clash.autotunnel.AutoTunnelRule
import com.follow.clash.autotunnel.AutoTunnelRules
import com.follow.clash.autotunnel.AutoTunnelSettings
import com.follow.clash.autotunnel.FallbackMode
import com.follow.clash.autotunnel.NetworkKind
import com.follow.clash.autotunnel.NetworkSnapshot
import com.follow.clash.autotunnel.TriggerKind
import com.follow.clash.autotunnel.TunnelTarget
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTunnelRulesTest {
    private val warp = TunnelTarget(profileId = 1)
    private val auckland = TunnelTarget(profileId = 2, group = "Windscribe", proxy = "NZ Auckland")
    private val uni = AutoTunnelRule("uni", TriggerKind.WIFI, ssids = listOf("eduroam"), target = warp)
    private val sim2 = AutoTunnelRule("sim2", TriggerKind.MOBILE, simIds = listOf(2), target = auckland)
    private val bank = AutoTunnelRule("bank", TriggerKind.APP, apps = listOf("nz.bank"), target = auckland)
    private val settings = AutoTunnelSettings(enabled = true, rules = listOf(uni, sim2, bank))

    @Test
    fun `network rules pick their tunnel`() {
        assertEquals(
            "uni",
            AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.WIFI, "eduroam"))?.key,
        )
        assertEquals(
            auckland,
            AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = 2))
                ?.target,
        )
        assertNull(AutoTunnelRules.decide(settings, NetworkSnapshot(NetworkKind.CELLULAR, subscriptionId = 1)))
    }

    @Test
    fun `an open rule app wins over the network`() {
        val decision = AutoTunnelRules.decide(
            settings,
            NetworkSnapshot(NetworkKind.WIFI, "eduroam"),
            openApps = listOf("nz.bank"),
        )
        assertEquals("bank", decision?.key)
    }

    @Test
    fun `the fallback applies when nothing holds, but not without a network`() {
        val off = settings.copy(fallback = FallbackMode.DISCONNECT)
        val decision = AutoTunnelRules.decide(off, NetworkSnapshot(NetworkKind.WIFI, "Cafe"))
        assertEquals(AutoTunnelRules.FALLBACK, decision?.key)
        assertNull(decision?.target?.profileId)
        assertNull(AutoTunnelRules.decide(off, NetworkSnapshot(NetworkKind.NONE)))
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
        recent = AutoTunnelRules.stepOpenApps(listOf("nz.bank"), "launcher", null, apps)
        assertEquals(emptyList<String>(), recent)
    }

    @Test
    fun `settings read the JSON Flutter writes`() {
        val json = """{"enabled":true,"rules":[{"id":"a","kind":"mobile","enabled":true,
            |"ssids":[],"simIds":[3],"apps":[],"target":{"profileId":5,"group":"G","proxy":"P"}}],
            |"fallback":"target","fallbackTarget":{"profileId":null,"group":null,"proxy":null},
            |"rendered":{"a":{"profileId":5,"path":"/x.yaml","label":"L","selectedMap":{"G":"P"}}}}"""
            .trimMargin()
        val parsed = Gson().fromJson(json, AutoTunnelSettings::class.java)
        assertEquals(TriggerKind.MOBILE, parsed.rules.single().kind)
        assertEquals(5L, parsed.rules.single().target.profileId)
        assertEquals(FallbackMode.TARGET, parsed.fallback)
        assertEquals(mapOf("G" to "P"), parsed.rendered["a"]?.selectedMap)
    }
}
