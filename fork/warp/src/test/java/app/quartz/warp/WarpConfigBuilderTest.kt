package app.quartz.warp

import app.quartz.warp.awg.AwgPayloads
import com.wgtunnel.parser.Config
import com.wgtunnel.parser.crypto.Key
import java.time.Instant
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpConfigBuilderTest {
    private val privateKey = Key.generatePrivateKey().toBase64()
    private val registration =
        WarpRegistration(
            id = "reg",
            token = "token",
            apiVersion = "v0a737",
            privateKey = privateKey,
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            endpointHost = "engage.cloudflareclient.com:2408",
            addressV4 = "172.16.0.2",
            addressV6 = "2606:4700:110:8a36:df92:102a:9602:fa18",
            accountType = "free",
            ttlAt = null,
        )
    private val expires = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()

    private fun build(variant: WarpVariant, options: WarpOptions = WarpOptions()): String {
        val awg = AwgPayloads.pick(emptyList(), "www.google.com", Random(1))
        return WarpConfigBuilder.build(
            registration,
            variant,
            options,
            registration.endpointHost,
            awg,
            expires,
        )
    }

    @Test
    fun amneziaConfigParsesWithUpstreamParser() {
        val text = build(WarpVariant.AWG)
        val config = Config.parseQuickString(text)
        val iface = config.`interface`
        assertEquals(privateKey, iface.privateKey)
        assertEquals(1280, iface.mtu)
        assertEquals(4, iface.jC)
        assertEquals(40, iface.jMin)
        assertEquals(70, iface.jMax)
        assertEquals(0, iface.s1)
        assertEquals(0, iface.s2)
        assertEquals("1", iface.h1)
        assertEquals("4", iface.h4)
        assertTrue(iface.i1!!.startsWith("<b 0x"))
        assertTrue(iface.i5!!.startsWith("<b 0x"))
        assertEquals("engage.cloudflareclient.com:2408", config.peers.single().endpoint)
    }

    @Test
    fun markersSurviveUpstreamRoundTrip() {
        val roundTripped = Config.parseQuickString(build(WarpVariant.AWG)).asQuickString()
        assertEquals(WarpMarkers("warp", WarpVariant.AWG, expires), WarpMarkers.parse(roundTripped))
    }

    @Test
    fun wireguardConfigHasNoAmneziaFields() {
        val text = build(WarpVariant.WG)
        val iface = Config.parseQuickString(text).`interface`
        assertNull(iface.jC)
        assertNull(iface.i1)
        assertEquals(WarpMarkers("warp", WarpVariant.WG, expires), WarpMarkers.parse(text))
    }

    @Test
    fun markersWriteAndStrip() {
        val text = build(WarpVariant.WG)
        val moved = WarpMarkers.write(text, WarpMarkers("warp-2", WarpVariant.WG, null))
        assertEquals(WarpMarkers("warp-2", WarpVariant.WG, null), WarpMarkers.parse(moved))
        assertNull(WarpMarkers.parse(WarpMarkers.strip(text)))
        Config.parseQuickString(moved)
    }

    @Test
    fun effectiveExpiryIsTheEarlier() {
        assertEquals(5L, WarpConfigBuilder.effectiveExpiry(5L, 9L))
        assertEquals(9L, WarpConfigBuilder.effectiveExpiry(null, 9L))
        assertNull(WarpConfigBuilder.effectiveExpiry(null, null))
    }

    @Test
    fun slotsAndNames() {
        val taken = setOf("warp", "warp-2")
        assertEquals("warp-3", WarpRepository.nextFreeSlot("warp", taken::contains))
        assertEquals("warp-3", WarpRepository.nextFreeSlot("warp-2", taken::contains))
        assertEquals("WARP (1)", WarpRepository.uniqueName("WARP", setOf("WARP")))
        assertEquals("WARP AWG (phone)", WarpConfigBuilder.tunnelName("phone", WarpVariant.AWG))
    }

    @Test
    fun parsesBareAndWrappedRegistrations() {
        val bare =
            """{"id":"abc","token":"t","account":{"account_type":"free","ttl":"0001-01-01T00:00:00Z"},
            "config":{"peers":[{"public_key":"pk","endpoint":{"v4":"162.159.192.5:0","host":"engage.cloudflareclient.com:2408"}}],
            "interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700::2"}}}}"""
        val parsed =
            CloudflareWarpApi.parseRegistration(
                Json.parseToJsonElement(bare).jsonObject,
                "v0a737",
                "priv",
            )
        assertEquals("abc", parsed.id)
        assertEquals("engage.cloudflareclient.com:2408", parsed.endpointHost)
        assertNull("unset TTL must not count as an expiry", parsed.ttlAt)

        val wrapped = Json.parseToJsonElement("""{"result":$bare,"success":true}""").jsonObject
        assertEquals(
            "2606:4700::2",
            CloudflareWarpApi.parseRegistration(wrapped, "v0a737", "priv").addressV6,
        )
    }

    @Test
    fun junkIsOrderedLikeTheSite() {
        val text = build(WarpVariant.AWG, WarpOptions(junk = Junk(4, 90, 30)))
        val iface = Config.parseQuickString(text).`interface`
        assertEquals(30, iface.jMin)
        assertEquals(90, iface.jMax)
    }

    @Test
    fun defaultsMatchTheSite() {
        val text = build(WarpVariant.WG)
        assertTrue(text.contains("DNS = 1.1.1.1, 1.0.0.1\n"))
        assertTrue("keepalive 0 writes no line", !text.contains("PersistentKeepalive"))
        assertEquals(24, WarpEndpoints.IPS.size)
        assertEquals(50, WarpEndpoints.PORTS.size)
    }

    @Test
    fun clashExportHasReservedAndAmneziaOptions() {
        val text = build(WarpVariant.AWG)
        val yaml = ClashExport.proxyYaml("WARP AWG", text, clientId = "AQID")
        assertTrue(yaml.startsWith("proxies:\n  - name: \"WARP AWG\"\n    type: wireguard\n"))
        assertTrue(yaml.contains("    server: \"engage.cloudflareclient.com\"\n    port: 2408\n"))
        assertTrue(yaml.contains("    ip: 172.16.0.2\n"))
        assertTrue(yaml.contains("    reserved: [1, 2, 3]\n"))
        assertTrue(yaml.contains("      jc: 4\n"))
        assertTrue(yaml.contains("      i1: \"<b 0x"))
        assertNull(ClashExport.reserved("not base64!"))
    }

    @Test
    fun apiErrorsAndCidrAddresses() {
        val error =
            Json.parseToJsonElement(
                    """{"success":false,"errors":[{"code":1,"message":"Too many"}]}"""
                )
                .jsonObject
        assertEquals("Too many", CloudflareWarpApi.apiErrorMessage(error))
        val reg =
            """{"id":"a","token":"t","account":{"account_type":"limited","warp_plus":true},
            "config":{"client_id":"AQID","peers":[{"public_key":"pk","endpoint":{}}],
            "interface":{"addresses":{"v4":"172.16.0.2/32","v6":"2606:4700::2/128"}}}}"""
        val parsed =
            CloudflareWarpApi.parseRegistration(
                Json.parseToJsonElement(reg).jsonObject,
                "v0a737",
                "priv",
            )
        assertEquals("172.16.0.2", parsed.addressV4)
        assertEquals("2606:4700::2", parsed.addressV6)
        assertEquals("WARP+ limited", parsed.accountType)
        assertEquals("AQID", parsed.clientId)
        assertEquals(WarpEndpoints.DEFAULT_HOST, parsed.endpointHost)
    }
}
