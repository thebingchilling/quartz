package app.quartz.engine.mihomo

import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Runs real mihomo (host build, no TUN) and drives it through [MihomoApi]. */
class MihomoApiTest {
    private lateinit var home: File
    private lateinit var api: MihomoApi

    @Before
    fun start() {
        val lib = File(System.getProperty("quartz.mihomo.hostLib").orEmpty())
        assumeTrue("host libmihomo not built (:app:buildMihomoHost)", lib.isFile)
        System.load(lib.path)
        home = kotlin.io.path.createTempDirectory("mihomo").toFile()
        val profile =
            File(home, "profile.yaml").apply {
                writeText(
                    """
                    mode: rule
                    proxies:
                      - {name: dead-ss, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: x}
                    proxy-groups:
                      - {name: Proxy, type: select, proxies: [Auto, dead-ss, DIRECT]}
                      - {name: Auto, type: url-test, proxies: [dead-ss], url: "https://cp.cloudflare.com/generate_204", interval: 300}
                    rules:
                      - MATCH,Proxy
                    """
                        .trimIndent()
                )
            }
        val port = ServerSocket(0).use { it.localPort }
        val controller = MihomoController("127.0.0.1", port, "test-secret")
        val error =
            MihomoNative.start(home.path, profile.path, -1, "127.0.0.1:$port", controller.secret) {
                true
            }
        assertNull(error)
        api = MihomoApi(controller)
    }

    @After
    fun stop() {
        if (::api.isInitialized) MihomoNative.stop()
        if (::home.isInitialized) home.deleteRecursively()
    }

    @Test
    fun groupsSelectionAndMode() = runBlocking {
        val proxies = api.proxies()
        assertEquals(listOf("Proxy", "Auto", MihomoApi.GLOBAL), proxies.groups.map { it.name })
        val proxy = proxies.byName.getValue("Proxy")
        assertTrue(proxy.isSelectable)
        assertEquals(listOf("Auto", "dead-ss", "DIRECT"), proxy.all)
        assertEquals("Auto", proxy.now)

        api.select("Proxy", "DIRECT")
        assertEquals("DIRECT", api.proxies().byName.getValue("Proxy").now)

        api.setMode(MihomoMode.GLOBAL)
        assertEquals(MihomoMode.GLOBAL, api.mode())

        assertNull("unreachable node has no delay", api.delay("dead-ss", timeoutMs = 300))
        assertTrue(api.version().isNotEmpty())
        assertTrue(api.providers().isEmpty())
    }
}
