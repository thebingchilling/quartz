package app.quartz.importer

import app.quartz.engine.mihomo.MihomoNative
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ImporterTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private val vmess =
        "vmess://" +
            b64(
                """{"v":"2","ps":"VM WS","add":"vm.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555",""" +
                    """"aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray","tls":"tls","sni":"vm.example.com"}"""
            )
    private val vless =
        "vless://11111111-2222-3333-4444-555555555555@vl.example.com:443?encryption=none&security=reality" +
            "&sni=www.microsoft.com&fp=chrome&pbk=PUBKEYPUBKEYPUBKEYPUBKEYPUBKEYPUBKEYPUBKEYP&sid=6ba85179e30d4fc2&type=tcp&flow=xtls-rprx-vision#VL%20Reality"
    private val trojan =
        "trojan://secret@tr.example.com:443?sni=tr.example.com&type=grpc&serviceName=gun#Trojan%20gRPC"
    private val ss = "ss://" + b64("aes-256-gcm:pass") + "@ss.example.com:8388#SS"
    private val ssLegacy = "ss://" + b64("chacha20-ietf-poly1305:p@ss@[2001:db8::1]:8388") + "#SS6"
    private val ssr =
        "ssr://" +
            b64(
                "ssr.example.com:443:auth_aes128_md5:aes-256-cfb:tls1.2_ticket_auth:" +
                    b64("pw") +
                    "/?remarks=" +
                    b64("SSR")
            )
    private val hy2 =
        "hy2://pw@hy.example.com:443?sni=hy.example.com&obfs=salamander&obfs-password=o#HY2"
    private val tuic =
        "tuic://11111111-2222-3333-4444-555555555555:pw@tu.example.com:443?congestion_control=bbr&alpn=h3&sni=tu.example.com#TUIC"
    private val anytls = "anytls://pw@any.example.com:443?sni=any.example.com#AnyTLS"
    private val socks = "socks5://user:pw@127.0.0.2:1080#Socks"
    private val wg =
        "wireguard://cFh0dGVybmF0aXZlUHJpdmF0ZUtleUJhc2U2NEVuY29kZWQ9@162.159.192.1:2408" +
            "?publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&address=172.16.0.2%2F32%2C2606%3A4700%3A%3A2%2F128&mtu=1280&reserved=1,2,3#WARP"

    private val allLinks =
        listOf(vmess, vless, trojan, ss, ssLegacy, ssr, hy2, tuic, anytls, socks, wg)

    @Test
    fun shareLinksConvert() {
        ShareLinks.parse(vmess).let {
            assertEquals("vmess", it["type"])
            assertEquals("ws", it["network"])
            assertEquals(
                mapOf("path" to "/ray", "headers" to mapOf("Host" to "cdn.example.com")),
                it["ws-opts"],
            )
            assertEquals(true, it["tls"])
        }
        ShareLinks.parse(vless).let {
            assertEquals("VL Reality", it["name"])
            assertEquals("xtls-rprx-vision", it["flow"])
            assertEquals("6ba85179e30d4fc2", (it["reality-opts"] as Map<*, *>)["short-id"])
        }
        ShareLinks.parse(trojan).let {
            assertEquals("gun", (it["grpc-opts"] as Map<*, *>)["grpc-service-name"])
        }
        ShareLinks.parse(ss).let {
            assertEquals("aes-256-gcm", it["cipher"])
            assertEquals("pass", it["password"])
        }
        ShareLinks.parse(ssLegacy).let {
            assertEquals("2001:db8::1", it["server"])
            assertEquals("p@ss", it["password"])
        }
        ShareLinks.parse(ssr).let {
            assertEquals("SSR", it["name"])
            assertEquals("auth_aes128_md5", it["protocol"])
            assertEquals("pw", it["password"])
        }
        ShareLinks.parse(wg).let {
            assertEquals("172.16.0.2", it["ip"])
            assertEquals("2606:4700::2", it["ipv6"])
            assertEquals(listOf(1, 2, 3), it["reserved"])
            assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", it["public-key"])
        }
    }

    @Test
    fun detectsEachKind() {
        assertTrue(
            ImportDetector.detect("[Interface]\nPrivateKey = x\n\n[Peer]\nPublicKey = y\n")
                is Detected.WireGuard
        )
        assertTrue(
            ImportDetector.detect("client\ndev tun\nremote vpn.example.com 1194\n")
                is Detected.OpenVpn
        )
        assertTrue(ImportDetector.detect("anything", "work.ovpn") is Detected.OpenVpn)
        assertEquals(
            Detected.SubscriptionUrl("https://sub.example.com/api?token=abc"),
            ImportDetector.detect(
                "clash://install-config?url=https%3A%2F%2Fsub.example.com%2Fapi%3Ftoken%3Dabc"
            ),
        )
        assertEquals(
            Detected.SubscriptionUrl("https://sub.example.com/link/abc"),
            ImportDetector.detect("https://sub.example.com/link/abc"),
        )
        // An HTTP proxy share link, not a subscription.
        assertTrue(
            ImportDetector.detect("http://user:pw@proxy.example.com:8080") is Detected.ProxyList
        )

        val links =
            ImportDetector.detect(allLinks.joinToString("\n") + "\nnot a link")
                as Detected.ProxyList
        assertEquals(allLinks.size, links.proxies.size)
        assertEquals(1, links.skipped.size)

        val base64 = ImportDetector.detect(b64(allLinks.joinToString("\n"))) as Detected.ProxyList
        assertEquals(Detected.ProxyList.Source.BASE64, base64.source)
        assertEquals(allLinks.size, base64.proxies.size)

        assertTrue((ImportDetector.detect(wg) as Detected.ProxyList).isSingleWireGuard)

        val full =
            ImportDetector.detect(
                "proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p}\nrules:\n  - MATCH,DIRECT\n"
            )
        assertEquals(Detected.ClashProfile::class, full::class)
        val bare =
            ImportDetector.detect(
                "proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p}\n"
            )
        assertEquals(1, (bare as Detected.ProxyList).proxies.size)

        assertTrue(ImportDetector.detect("hello world") is Detected.Unknown)
    }

    @Test
    fun singBoxOutbounds() {
        val json =
            """{"outbounds":[
              {"type":"vless","tag":"sb-vless","server":"a.example.com","server_port":443,"uuid":"11111111-2222-3333-4444-555555555555",
               "tls":{"enabled":true,"server_name":"a.example.com","reality":{"enabled":true,"public_key":"PK","short_id":"ab"}}},
              {"type":"shadowsocks","tag":"sb-ss","server":"b.example.com","server_port":8388,"method":"aes-128-gcm","password":"p"},
              {"type":"ssh","tag":"sb-ssh","server":"c.example.com","server_port":22},
              {"type":"direct","tag":"direct"}]}"""
        val detected = ImportDetector.detect(json) as Detected.ProxyList
        assertEquals(listOf("sb-vless", "sb-ss"), detected.proxies.map { it["name"] })
        assertEquals(listOf("sb-ssh (ssh)"), detected.skipped)
    }

    @Test
    fun subscriptionHeaders() {
        val info =
            SubscriptionInfo.fromHeaders(
                mapOf(
                    "Subscription-Userinfo" to
                        "upload=1024; download=2048; total=10737418240; expire=1798761600",
                    "profile-update-interval" to "24",
                    "Content-Disposition" to
                        "attachment; filename=\"plain.yaml\"; filename*=UTF-8''%E6%B5%8B%E8%AF%95.yaml",
                )
            )
        assertEquals(3072L, info.usedBytes)
        assertEquals(10737418240L, info.totalBytes)
        assertEquals(1798761600L, info.expireAt)
        assertEquals(24, info.updateIntervalHours)
        assertEquals("测试", info.name)
    }

    @Test
    fun templatesAndUniqueNames() {
        val proxies = allLinks.map(ShareLinks::parse) + ShareLinks.parse(ss)
        val yaml = ClashTemplate.build(ClashTemplate.RuleBased("nz"), proxies)
        val mapping = ClashYaml.parseMapping(yaml)!!
        val names = ClashYaml.proxies(mapping).map { it["name"] }
        assertEquals(names.size, names.toSet().size)
        assertTrue("SS 2" in names)
        assertTrue("GEOIP,NZ,DIRECT" in (mapping["rules"] as List<*>))
        val custom =
            ClashTemplate.build(
                ClashTemplate.Custom(
                    "proxies:{{proxies}}\nproxy-groups:\n  - {name: G, type: select, proxies: {{proxy-names}}}\nrules: [MATCH,G]\n"
                ),
                proxies,
            )
        assertEquals(proxies.size, ClashYaml.proxies(ClashYaml.parseMapping(custom)!!).size)
    }

    /** Every generated profile must be one real mihomo accepts (needs :app:buildMihomoHost). */
    @Test
    fun mihomoAcceptsGeneratedProfiles() {
        val lib = File(System.getProperty("quartz.mihomo.hostLib").orEmpty())
        assumeTrue("host libmihomo not built", lib.isFile)
        System.load(lib.path)
        val home = kotlin.io.path.createTempDirectory("mihomo").toFile()
        val proxies = allLinks.map(ShareLinks::parse)
        listOf(ClashTemplate.Simple, ClashTemplate.RuleBased("nz")).forEach { template ->
            // RuleBased needs geodata, which mihomo downloads on first use; Simple is fully
            // offline.
            val profile =
                File(home, "profile.yaml").apply {
                    writeText(ClashTemplate.build(template, proxies))
                }
            val error = MihomoNative.validate(home.path, profile.path)
            if (template == ClashTemplate.Simple) assertNull(error)
        }
        home.deleteRecursively()
    }
}
