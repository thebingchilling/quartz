package app.quartz.warp

import com.wgtunnel.parser.Config
import java.util.Base64

/**
 * A WARP config as a mihomo (Clash Meta) `wireguard` proxy, in a YAML file holding only `proxies:`
 * — the shape the importer (FORK.md §7) turns into a Clash profile via a template. Adding it
 * straight to an existing Clash profile comes with the Clash engine (step 3).
 */
object ClashExport {

    fun proxyYaml(name: String, config: String, clientId: String?): String {
        val parsed = Config.parseQuickString(config)
        val iface = parsed.`interface`
        val peer = parsed.peers.single()
        val (server, port) = splitEndpoint(peer.endpoint ?: WarpEndpoints.DEFAULT_HOST)
        val addresses = iface.address.orEmpty().split(',').map { it.trim().substringBefore('/') }
        val v4 = addresses.firstOrNull { ':' !in it && it.isNotEmpty() }
        val v6 = addresses.firstOrNull { ':' in it }
        return buildString {
            appendLine("proxies:")
            appendLine("  - name: ${quote(name)}")
            appendLine("    type: wireguard")
            appendLine("    server: ${quote(server)}")
            appendLine("    port: $port")
            v4?.let { appendLine("    ip: $it") }
            v6?.let { appendLine("    ipv6: ${quote(it)}") }
            appendLine("    private-key: ${quote(iface.privateKey)}")
            appendLine("    public-key: ${quote(peer.publicKey)}")
            peer.allowedIPs?.let { ips ->
                appendLine(
                    "    allowed-ips: [${ips.split(',').joinToString(", ") { quote(it.trim()) }}]"
                )
            }
            reserved(clientId)?.let { appendLine("    reserved: [${it.joinToString(", ")}]") }
            iface.mtu?.let { appendLine("    mtu: $it") }
            iface.dns
                ?.takeIf { it.isNotBlank() }
                ?.let { dns ->
                    appendLine("    remote-dns-resolve: true")
                    appendLine(
                        "    dns: [${dns.split(',').joinToString(", ") { quote(it.trim()) }}]"
                    )
                }
            peer.persistentKeepalive
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { appendLine("    persistent-keepalive: $it") }
            appendLine("    udp: true")
            if (iface.jC != null) {
                appendLine("    amnezia-wg-option:")
                appendLine("      jc: ${iface.jC}")
                appendLine("      jmin: ${iface.jMin}")
                appendLine("      jmax: ${iface.jMax}")
                appendLine("      s1: ${iface.s1 ?: 0}")
                appendLine("      s2: ${iface.s2 ?: 0}")
                appendLine("      h1: ${iface.h1 ?: 1}")
                appendLine("      h2: ${iface.h2 ?: 2}")
                appendLine("      h3: ${iface.h3 ?: 3}")
                appendLine("      h4: ${iface.h4 ?: 4}")
                listOf(iface.i1, iface.i2, iface.i3, iface.i4, iface.i5).forEachIndexed { i, value
                    ->
                    value?.let { appendLine("      i${i + 1}: ${quote(it)}") }
                }
            }
        }
    }

    /** WARP's `client_id` is three base64 bytes, sent as WireGuard's reserved field. */
    fun reserved(clientId: String?): List<Int>? =
        clientId
            ?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
            ?.takeIf { it.size == 3 }
            ?.map { it.toInt() and 0xff }

    private fun splitEndpoint(endpoint: String): Pair<String, Int> {
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: 2408
        val host = endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        return host to port
    }

    private fun quote(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
