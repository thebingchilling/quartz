package app.quartz.importer

import java.net.URI
import java.net.URLDecoder

/** What one Import was given, before anything is saved (FORK.md §7). */
sealed interface Detected {
    /** WireGuard/AmneziaWG `.conf`: upstream's own import. */
    data class WireGuard(val config: String) : Detected

    /** `.ovpn`: OpenVPN tunnel (engine in step 7). */
    data class OpenVpn(val config: String) : Detected

    /** A complete Clash/mihomo profile, kept as-is. */
    data class ClashProfile(val yaml: String, val proxyCount: Int) : Detected

    /** Proxies without groups or rules: becomes a profile through a [ClashTemplate]. */
    data class ProxyList(
        val proxies: List<ProxyNode>,
        val providers: Map<String, Any?> = emptyMap(),
        val skipped: List<String> = emptyList(),
        val source: Source,
    ) : Detected {
        enum class Source {
            YAML,
            SHARE_LINKS,
            BASE64,
            SING_BOX,
        }

        /** A lone wireguard:// link asks: WireGuard tunnel or Clash node. */
        val isSingleWireGuard: Boolean
            get() = proxies.size == 1 && proxies[0]["type"] == "wireguard"
    }

    /** A subscription to fetch; its content is detected again after download. */
    data class SubscriptionUrl(val url: String) : Detected

    data class Unknown(val reason: String) : Detected
}

object ImportDetector {

    fun detect(content: String, fileName: String? = null): Detected {
        val text = content.removePrefix("﻿").trim()
        if (text.isEmpty()) return Detected.Unknown("Empty")
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()

        if (isWireGuard(text)) return Detected.WireGuard(text)
        if (ext == "ovpn" || isOpenVpn(text)) return Detected.OpenVpn(text)

        deepLinkSubscription(text)?.let {
            return Detected.SubscriptionUrl(it)
        }
        if (isSubscriptionUrl(text)) return Detected.SubscriptionUrl(text)

        if (text.startsWith("{") && SingBox.isSingBox(text)) {
            val converted = SingBox.convert(text)
            return if (converted.proxies.isEmpty())
                Detected.Unknown("No supported sing-box outbounds")
            else
                Detected.ProxyList(
                    converted.proxies,
                    skipped = converted.skipped,
                    source = Detected.ProxyList.Source.SING_BOX,
                )
        }

        shareLinks(text, Detected.ProxyList.Source.SHARE_LINKS)?.let {
            return it
        }

        ClashYaml.parseMapping(text)?.let { mapping ->
            if (ClashYaml.hasProxies(mapping) || ClashYaml.isFullProfile(mapping)) {
                return if (ClashYaml.isFullProfile(mapping))
                    Detected.ClashProfile(text, ClashYaml.proxies(mapping).size)
                else
                    Detected.ProxyList(
                        ClashYaml.proxies(mapping),
                        providers =
                            (mapping["proxy-providers"] as? Map<*, *>)
                                ?.entries
                                ?.associate { it.key.toString() to it.value }
                                .orEmpty(),
                        source = Detected.ProxyList.Source.YAML,
                    )
            }
        }

        // A base64 subscription body: decode, then it's share links (or occasionally YAML).
        runCatching { ShareLinks.decodeBase64(text) }
            .getOrNull()
            ?.takeIf { "://" in it }
            ?.let { decoded ->
                shareLinks(decoded, Detected.ProxyList.Source.BASE64)?.let {
                    return it
                }
                return when (val inner = detect(decoded, fileName)) {
                    is Detected.Unknown ->
                        Detected.Unknown("Nothing recognisable in the decoded content")
                    else -> inner
                }
            }

        return Detected.Unknown(
            "Not a WireGuard, OpenVPN, Clash or sing-box config, share link or subscription"
        )
    }

    fun isWireGuard(text: String): Boolean =
        Regex("(?im)^\\s*\\[Interface]\\s*$").containsMatchIn(text) &&
            Regex("(?im)^\\s*\\[Peer]\\s*$").containsMatchIn(text)

    fun isOpenVpn(text: String): Boolean =
        Regex("(?im)^\\s*client\\s*$").containsMatchIn(text) &&
            Regex("(?im)^\\s*remote\\s+\\S+").containsMatchIn(text) ||
            ("<ca>" in text && Regex("(?im)^\\s*remote\\s+\\S+").containsMatchIn(text))

    /** `clash://install-config?url=…` and `clashmeta://…` deep links. */
    fun deepLinkSubscription(text: String): String? {
        if (text.lines().size != 1) return null
        val scheme = text.substringBefore("://").lowercase()
        if (scheme != "clash" && scheme != "clashmeta") return null
        val query = text.substringAfter('?', "")
        return query
            .split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * One http(s) URL with a path or query and no credentials. `http://user:pass@host:port` is an
     * HTTP proxy share link instead.
     */
    fun isSubscriptionUrl(text: String): Boolean {
        if (text.lines().size != 1 || text.contains(' ')) return false
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return false
        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        if (uri.host == null || uri.rawUserInfo != null) return false
        return !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/" || uri.rawQuery != null
    }

    /** Lines of share links → a proxy list; null when no line is a share link. */
    private fun shareLinks(text: String, source: Detected.ProxyList.Source): Detected.ProxyList? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val links = lines.filter {
            ShareLinks.scheme(it) != null && !(lines.size == 1 && isSubscriptionUrl(it))
        }
        if (links.isEmpty() || links.size * 2 < lines.size) return null
        val proxies = mutableListOf<ProxyNode>()
        val skipped = mutableListOf<String>()
        for (link in links) {
            runCatching { ShareLinks.parse(link) }
                .onSuccess { proxies += it }
                .onFailure { skipped += "${link.substringBefore("://")}:// (${it.message})" }
        }
        skipped += (lines - links.toSet()).map { it.take(40) }
        return Detected.ProxyList(proxies, skipped = skipped, source = source)
    }
}
