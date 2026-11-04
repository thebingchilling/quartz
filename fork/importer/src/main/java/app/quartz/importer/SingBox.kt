package app.quartz.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** sing-box `outbounds` converted where mihomo has an equivalent (FORK.md §7). */
object SingBox {
    data class Converted(val proxies: List<ProxyNode>, val skipped: List<String>)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Non-proxy outbound types: silently ignored, not reported as skipped. */
    private val BUILTIN = setOf("direct", "block", "dns", "selector", "urltest")

    fun isSingBox(text: String): Boolean = runCatching {
        (json.parseToJsonElement(text) as? JsonObject)?.get("outbounds") is JsonArray
    }
        .getOrDefault(false)

    fun convert(text: String): Converted {
        val outbounds =
            (json.parseToJsonElement(text).jsonObject["outbounds"] as? JsonArray).orEmpty()
        val proxies = mutableListOf<ProxyNode>()
        val skipped = mutableListOf<String>()
        for (element in outbounds) {
            val o = element as? JsonObject ?: continue
            val type = o.s("type") ?: continue
            if (type in BUILTIN) continue
            val tag = o.s("tag") ?: type
            runCatching { outbound(type, tag, o) }
                .onSuccess { proxies += it }
                .onFailure { skipped += "$tag ($type)" }
        }
        return Converted(proxies, skipped)
    }

    private fun outbound(type: String, tag: String, o: JsonObject): ProxyNode {
        val node =
            ProxyNode().apply {
                put("name", tag)
                put("type", mihomoType(type))
                put("server", o.s("server") ?: error("no server"))
                put("port", o.i("server_port") ?: error("no port"))
            }
        when (type) {
            "shadowsocks" -> {
                node["cipher"] = o.s("method")
                node["password"] = o.s("password")
            }
            "vmess" -> {
                node["uuid"] = o.s("uuid")
                node["alterId"] = o.i("alter_id") ?: 0
                node["cipher"] = o.s("security") ?: "auto"
            }
            "vless" -> {
                node["uuid"] = o.s("uuid")
                o.s("flow")?.let { node["flow"] = it }
            }
            "trojan",
            "hysteria2",
            "anytls" -> node["password"] = o.s("password")
            "hysteria" -> {
                o.s("auth_str")?.let { node["auth-str"] = it }
                o.i("up_mbps")?.let { node["up"] = it }
                o.i("down_mbps")?.let { node["down"] = it }
                o.s("obfs")?.let { node["obfs"] = it }
            }
            "tuic" -> {
                node["uuid"] = o.s("uuid")
                node["password"] = o.s("password")
                o.s("congestion_control")?.let { node["congestion-controller"] = it }
                o.s("udp_relay_mode")?.let { node["udp-relay-mode"] = it }
            }
            "socks",
            "http" -> {
                o.s("username")?.let { node["username"] = it }
                o.s("password")?.let { node["password"] = it }
            }
            "wireguard" -> {
                node["private-key"] = o.s("private_key")
                node["public-key"] = o.s("peer_public_key")
                (o["local_address"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.forEach {
                        val ip = it.substringBefore('/')
                        node[if (':' in ip) "ipv6" else "ip"] = ip
                    }
                o.i("mtu")?.let { node["mtu"] = it }
            }
            else -> error("unsupported")
        }
        if (type == "hysteria2")
            (o["obfs"] as? JsonObject)?.let {
                node["obfs"] = it.s("type")
                node["obfs-password"] = it.s("password")
            }
        (o["tls"] as? JsonObject)
            ?.takeIf { it.b("enabled") == true }
            ?.let { tls ->
                if (type in setOf("vmess", "vless", "http")) node["tls"] = true
                val sniKey = if (type in setOf("vmess", "vless")) "servername" else "sni"
                tls.s("server_name")?.let { node[sniKey] = it }
                if (tls.b("insecure") == true) node["skip-cert-verify"] = true
                (tls["alpn"] as? JsonArray)?.let {
                    node["alpn"] = it.mapNotNull { a -> (a as? JsonPrimitive)?.contentOrNull }
                }
                (tls["utls"] as? JsonObject)?.s("fingerprint")?.let {
                    node["client-fingerprint"] = it
                }
                (tls["reality"] as? JsonObject)
                    ?.takeIf { it.b("enabled") == true }
                    ?.let { reality ->
                        node["reality-opts"] =
                            linkedMapOf<String, Any?>("public-key" to reality.s("public_key"))
                                .apply { reality.s("short_id")?.let { put("short-id", it) } }
                    }
            }
        (o["transport"] as? JsonObject)?.let { t ->
            when (t.s("type")) {
                "ws" -> {
                    node["network"] = "ws"
                    node["ws-opts"] =
                        linkedMapOf<String, Any?>().apply {
                            t.s("path")?.let { put("path", it) }
                            (t["headers"] as? JsonObject)?.s("Host")?.let {
                                put("headers", linkedMapOf("Host" to it))
                            }
                        }
                }
                "grpc" -> {
                    node["network"] = "grpc"
                    node["grpc-opts"] =
                        linkedMapOf<String, Any?>(
                            "grpc-service-name" to (t.s("service_name") ?: "")
                        )
                }
                "http" -> {
                    node["network"] = "h2"
                    node["h2-opts"] =
                        linkedMapOf<String, Any?>().apply { t.s("path")?.let { put("path", it) } }
                }
                else -> error("unsupported transport")
            }
        }
        if (type !in setOf("http")) node["udp"] = true
        return node
    }

    private fun mihomoType(type: String) =
        when (type) {
            "shadowsocks" -> "ss"
            "socks" -> "socks5"
            else -> type
        }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.i(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
