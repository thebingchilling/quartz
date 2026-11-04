package app.quartz.importer

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A mihomo `proxies:` entry, in mihomo's own field names. Insertion order is kept for YAML. */
typealias ProxyNode = LinkedHashMap<String, Any?>

class ShareLinkException(message: String) : Exception(message)

/**
 * Converts proxy share links to mihomo proxies (FORK.md §7): vmess, vless, trojan, ss, ssr,
 * hysteria, hysteria2/hy2, tuic, anytls, socks5, http(s) and wireguard/wg.
 */
object ShareLinks {
    val SCHEMES =
        setOf(
            "vmess",
            "vless",
            "trojan",
            "ss",
            "ssr",
            "hysteria",
            "hysteria2",
            "hy2",
            "tuic",
            "anytls",
            "socks",
            "socks5",
            "http",
            "https",
            "wireguard",
            "wg",
        )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun scheme(link: String): String? =
        link.substringBefore("://", "").lowercase().takeIf { it in SCHEMES }

    fun parse(link: String): ProxyNode {
        val trimmed = link.trim()
        return when (scheme(trimmed)) {
            "vmess" -> vmess(trimmed)
            "vless" -> vless(trimmed)
            "trojan" -> trojan(trimmed)
            "ss" -> shadowsocks(trimmed)
            "ssr" -> shadowsocksR(trimmed)
            "hysteria" -> hysteria(trimmed)
            "hysteria2",
            "hy2" -> hysteria2(trimmed)
            "tuic" -> tuic(trimmed)
            "anytls" -> anytls(trimmed)
            "socks",
            "socks5" -> socks(trimmed)
            "http",
            "https" -> http(trimmed)
            "wireguard",
            "wg" -> wireguard(trimmed)
            else -> throw ShareLinkException("Unsupported link")
        }
    }

    private fun vmess(link: String): ProxyNode {
        val body = link.removePrefix("vmess://").substringBefore('#')
        val obj = runCatching {
            json.parseToJsonElement(decodeBase64(body)).jsonObject
        }.getOrElse { throw ShareLinkException("vmess link isn't base64 JSON") }
        fun s(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val server = s("add") ?: throw ShareLinkException("vmess link has no server")
        val node =
            node(s("ps") ?: server, "vmess", server, s("port")?.toIntOrNull() ?: missing("port"))
        node["uuid"] = s("id") ?: missing("id")
        node["alterId"] = s("aid")?.toIntOrNull() ?: 0
        node["cipher"] = s("scy") ?: "auto"
        node["udp"] = true
        if (s("tls") == "tls") {
            node["tls"] = true
            s("sni")?.let { node["servername"] = it }
            s("alpn")?.let { node["alpn"] = it.split(',') }
            s("fp")?.let { node["client-fingerprint"] = it }
        }
        transport(node, s("net") ?: "tcp", s("path"), s("host"), s("path"), s("type"))
        return node
    }

    private fun vless(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "vless", host(uri), port(uri))
        node["uuid"] = userInfo(uri) ?: missing("uuid")
        node["udp"] = true
        q["flow"]?.let { node["flow"] = it }
        when (q["security"]) {
            "tls",
            "xtls" -> tls(node, q, sniKey = "servername")
            "reality" -> {
                tls(node, q, sniKey = "servername")
                node["reality-opts"] =
                    linkedMapOf<String, Any?>("public-key" to q["pbk"]).apply {
                        q["sid"]?.let { put("short-id", it) }
                    }
            }
        }
        transport(node, q["type"] ?: "tcp", q["path"], q["host"], q["serviceName"], q["headerType"])
        return node
    }

    private fun trojan(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "trojan", host(uri), port(uri))
        node["password"] = userInfo(uri) ?: missing("password")
        node["udp"] = true
        tls(node, q, sniKey = "sni", always = true)
        transport(node, q["type"] ?: "tcp", q["path"], q["host"], q["serviceName"], null)
        return node
    }

    private fun shadowsocks(link: String): ProxyNode {
        val body = link.removePrefix("ss://")
        val fragment = body.substringAfter('#', "").let(::decode)
        val main = body.substringBefore('#')
        val (beforeQuery, queryString) = main.substringBefore('?') to main.substringAfter('?', "")
        // SIP002: ss://base64(method:password)@host:port, or legacy
        // ss://base64(method:password@host:port)
        val (userPart, hostPart) =
            if ('@' in beforeQuery)
                beforeQuery.substringBeforeLast('@') to beforeQuery.substringAfterLast('@')
            else
                decodeBase64(beforeQuery).let {
                    it.substringBeforeLast('@') to it.substringAfterLast('@')
                }
        val credentials = if (':' in decode(userPart)) decode(userPart) else decodeBase64(userPart)
        val cipher = credentials.substringBefore(':')
        val password = credentials.substringAfter(':')
        val (server, port) = hostPort(hostPart.trimEnd('/'))
        val node = node(fragment.ifEmpty { server }, "ss", server, port)
        node["cipher"] = cipher
        node["password"] = password
        node["udp"] = true
        parseQuery(queryString)["plugin"]?.let { plugin ->
            val parts = plugin.split(';')
            val opts =
                parts.drop(1).associate {
                    it.substringBefore('=') to it.substringAfter('=', "true")
                }
            when (parts.first()) {
                "obfs-local",
                "simple-obfs" -> {
                    node["plugin"] = "obfs"
                    node["plugin-opts"] =
                        linkedMapOf<String, Any?>("mode" to (opts["obfs"] ?: "http")).apply {
                            opts["obfs-host"]?.let { put("host", it) }
                        }
                }
                "v2ray-plugin" -> {
                    node["plugin"] = "v2ray-plugin"
                    node["plugin-opts"] =
                        linkedMapOf<String, Any?>("mode" to (opts["mode"] ?: "websocket")).apply {
                            if ("tls" in opts) put("tls", true)
                            opts["host"]?.let { put("host", it) }
                            opts["path"]?.let { put("path", it) }
                        }
                }
                else -> throw ShareLinkException("Unsupported ss plugin ${parts.first()}")
            }
        }
        return node
    }

    private fun shadowsocksR(link: String): ProxyNode {
        val decoded = decodeBase64(link.removePrefix("ssr://"))
        val main = decoded.substringBefore("/?").substringBefore('?')
        val params =
            parseQuery(decoded.substringAfter('?', ""), decode = false).mapValues {
                decodeBase64(it.value)
            }
        // host may be IPv6, so split from the right: host:port:protocol:method:obfs:password
        val fields = main.split(':')
        if (fields.size < 6) throw ShareLinkException("ssr link is incomplete")
        val password = decodeBase64(fields.last())
        val obfs = fields[fields.size - 2]
        val method = fields[fields.size - 3]
        val protocol = fields[fields.size - 4]
        val port = fields[fields.size - 5].toIntOrNull() ?: missing("port")
        val server = fields.dropLast(5).joinToString(":")
        val node = node(params["remarks"]?.ifEmpty { null } ?: server, "ssr", server, port)
        node["cipher"] = method
        node["password"] = password
        node["obfs"] = obfs
        node["protocol"] = protocol
        params["obfsparam"]?.takeIf { it.isNotEmpty() }?.let { node["obfs-param"] = it }
        params["protoparam"]?.takeIf { it.isNotEmpty() }?.let { node["protocol-param"] = it }
        node["udp"] = true
        return node
    }

    private fun hysteria(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "hysteria", host(uri), port(uri))
        q["auth"]?.let { node["auth-str"] = it }
        q["peer"]?.let { node["sni"] = it }
        q["upmbps"]?.let { node["up"] = it }
        q["downmbps"]?.let { node["down"] = it }
        q["alpn"]?.let { node["alpn"] = it.split(',') }
        q["obfs"]
            ?.takeIf { it.isNotEmpty() && it != "none" }
            ?.let { node["obfs"] = q["obfsParam"] ?: it }
        q["protocol"]?.let { node["protocol"] = it }
        if (q["insecure"] == "1") node["skip-cert-verify"] = true
        return node
    }

    private fun hysteria2(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "hysteria2", host(uri), port(uri, default = 443))
        userInfo(uri)?.let { node["password"] = it }
        q["sni"]?.let { node["sni"] = it }
        q["obfs"]?.takeIf { it != "none" }?.let { node["obfs"] = it }
        q["obfs-password"]?.let { node["obfs-password"] = it }
        q["alpn"]?.let { node["alpn"] = it.split(',') }
        q["pinSHA256"]?.let { node["fingerprint"] = it }
        q["mport"]?.let { node["ports"] = it }
        if (q["insecure"] == "1") node["skip-cert-verify"] = true
        return node
    }

    private fun tuic(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "tuic", host(uri), port(uri))
        val user = uri.rawUserInfo?.let(::decode) ?: missing("uuid")
        node["uuid"] = user.substringBefore(':')
        node["password"] = user.substringAfter(':', "")
        q["congestion_control"]?.let { node["congestion-controller"] = it }
        q["udp_relay_mode"]?.let { node["udp-relay-mode"] = it }
        q["alpn"]?.let { node["alpn"] = it.split(',') }
        q["sni"]?.let { node["sni"] = it }
        if (q["allow_insecure"] == "1" || q["insecure"] == "1") node["skip-cert-verify"] = true
        if (q["disable_sni"] == "1") node["disable-sni"] = true
        node["udp"] = true
        return node
    }

    private fun anytls(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "anytls", host(uri), port(uri))
        node["password"] = userInfo(uri) ?: missing("password")
        q["sni"]?.let { node["sni"] = it }
        q["fp"]?.let { node["client-fingerprint"] = it }
        if (q["insecure"] == "1") node["skip-cert-verify"] = true
        node["udp"] = true
        return node
    }

    private fun socks(link: String): ProxyNode {
        val uri = uri(link)
        val node = node(name(uri), "socks5", host(uri), port(uri))
        credentials(uri, node)
        node["udp"] = true
        return node
    }

    private fun http(link: String): ProxyNode {
        val uri = uri(link)
        val tls = uri.scheme.equals("https", ignoreCase = true)
        val node = node(name(uri), "http", host(uri), port(uri, default = if (tls) 443 else 80))
        credentials(uri, node)
        if (tls) node["tls"] = true
        return node
    }

    private fun wireguard(link: String): ProxyNode {
        val uri = uri(link)
        val q = query(uri)
        val node = node(name(uri), "wireguard", host(uri), port(uri))
        node["private-key"] = userInfo(uri) ?: q["privatekey"] ?: missing("private key")
        node["public-key"] = q["publickey"] ?: q["peer"] ?: missing("public key")
        val addresses =
            (q["address"] ?: q["ip"]).orEmpty().split(',').map { it.trim().substringBefore('/') }
        addresses.firstOrNull { ':' !in it && it.isNotEmpty() }?.let { node["ip"] = it }
        addresses.firstOrNull { ':' in it }?.let { node["ipv6"] = it }
        q["presharedkey"]?.let { node["pre-shared-key"] = it }
        q["mtu"]?.toIntOrNull()?.let { node["mtu"] = it }
        q["reserved"]?.let { reserved ->
            node["reserved"] =
                reserved.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 }
                    ?: reserved
        }
        node["udp"] = true
        return node
    }

    // --- helpers ---

    private fun node(name: String, type: String, server: String, port: Int) =
        ProxyNode().apply {
            put("name", name)
            put("type", type)
            put("server", server)
            put("port", port)
        }

    private fun tls(
        node: ProxyNode,
        q: Map<String, String>,
        sniKey: String,
        always: Boolean = false,
    ) {
        if (!always) node["tls"] = true
        (q["sni"] ?: q["peer"])?.let { node[sniKey] = it }
        q["fp"]?.let { node["client-fingerprint"] = it }
        q["alpn"]?.let { node["alpn"] = it.split(',') }
        if (q["allowInsecure"] == "1" || q["insecure"] == "1") node["skip-cert-verify"] = true
    }

    /** Fills network + its opts block from a link's transport fields. */
    private fun transport(
        node: ProxyNode,
        network: String,
        path: String?,
        host: String?,
        serviceName: String?,
        headerType: String?,
    ) {
        when (network) {
            "ws",
            "websocket" -> {
                node["network"] = "ws"
                node["ws-opts"] =
                    linkedMapOf<String, Any?>().apply {
                        path?.let { put("path", it) }
                        host?.let { put("headers", linkedMapOf("Host" to it)) }
                    }
            }
            "grpc" -> {
                node["network"] = "grpc"
                node["grpc-opts"] =
                    linkedMapOf<String, Any?>("grpc-service-name" to (serviceName ?: path ?: ""))
            }
            "h2",
            "http" -> {
                node["network"] = "h2"
                node["h2-opts"] =
                    linkedMapOf<String, Any?>().apply {
                        host?.let { put("host", it.split(',')) }
                        path?.let { put("path", it) }
                    }
            }
            "httpupgrade" -> {
                node["network"] = "ws"
                node["ws-opts"] =
                    linkedMapOf<String, Any?>("v2ray-http-upgrade" to true).apply {
                        path?.let { put("path", it) }
                        host?.let { put("headers", linkedMapOf("Host" to it)) }
                    }
            }
            "tcp" ->
                if (headerType == "http") {
                    node["network"] = "http"
                    node["http-opts"] =
                        linkedMapOf<String, Any?>().apply {
                            path?.let { put("path", it.split(',')) }
                            host?.let { put("headers", linkedMapOf("Host" to it.split(','))) }
                        }
                }
            else -> node["network"] = network
        }
    }

    private fun credentials(uri: URI, node: ProxyNode) {
        val user = uri.rawUserInfo?.let(::decode) ?: return
        val decoded =
            if (':' in user) user else runCatching { decodeBase64(user) }.getOrDefault(user)
        node["username"] = decoded.substringBefore(':')
        decoded.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { node["password"] = it }
    }

    private fun uri(link: String): URI = runCatching {
        URI(link.trim().replace(" ", "%20"))
    }.getOrElse { throw ShareLinkException("Malformed link") }

    private fun host(uri: URI) = uri.host?.removePrefix("[")?.removeSuffix("]") ?: missing("server")

    private fun port(uri: URI, default: Int? = null) =
        uri.port.takeIf { it > 0 } ?: default ?: missing("port")

    private fun name(uri: URI) = uri.rawFragment?.let(::decode)?.ifEmpty { null } ?: host(uri)

    private fun userInfo(uri: URI) = uri.rawUserInfo?.let(::decode)?.ifEmpty { null }

    private fun query(uri: URI) = parseQuery(uri.rawQuery.orEmpty())

    private fun parseQuery(raw: String, decode: Boolean = true): Map<String, String> =
        raw.split('&')
            .filter { it.isNotEmpty() }
            .associate {
                val key = it.substringBefore('=')
                val value = it.substringAfter('=', "")
                key to if (decode) decode(value) else value
            }

    private fun hostPort(value: String): Pair<String, Int> {
        val port = value.substringAfterLast(':').toIntOrNull() ?: missing("port")
        return value.substringBeforeLast(':').removePrefix("[").removeSuffix("]") to port
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)

    private fun missing(what: String): Nothing = throw ShareLinkException("Link has no $what")

    /** Standard or URL-safe base64, padded or not. */
    fun decodeBase64(value: String): String {
        val clean = value.trim().replace("\n", "").replace("\r", "").trimEnd('=')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val bytes = runCatching {
            Base64.getDecoder().decode(padded)
        }
            .recoverCatching { Base64.getUrlDecoder().decode(padded) }
            .getOrElse { throw ShareLinkException("Not base64") }
        return bytes.decodeToString()
    }
}
