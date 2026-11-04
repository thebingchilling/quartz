package app.quartz.importer

import java.net.URLDecoder

/** What a subscription response says about itself in its headers (FORK.md §7, subscriptions). */
data class SubscriptionInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Epoch seconds. */
    val expireAt: Long? = null,
    val updateIntervalHours: Int? = null,
    val name: String? = null,
    val webPageUrl: String? = null,
) {
    val usedBytes: Long?
        get() =
            if (uploadBytes == null && downloadBytes == null) null
            else (uploadBytes ?: 0) + (downloadBytes ?: 0)

    companion object {
        /** mihomo/Clash Meta's User-Agent, which most providers key their response format on. */
        const val USER_AGENT = "clash.meta/1.19.33"

        /** Parses headers (names case-insensitive). */
        fun fromHeaders(headers: Map<String, String>): SubscriptionInfo {
            val h = headers.mapKeys { it.key.lowercase() }
            val userInfo =
                h["subscription-userinfo"]
                    .orEmpty()
                    .split(';')
                    .mapNotNull { part ->
                        val key = part.substringBefore('=').trim().lowercase()
                        val value = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
                        value?.let { key to it }
                    }
                    .toMap()
            return SubscriptionInfo(
                uploadBytes = userInfo["upload"],
                downloadBytes = userInfo["download"],
                totalBytes = userInfo["total"]?.takeIf { it > 0 },
                expireAt = userInfo["expire"]?.takeIf { it > 0 },
                updateIntervalHours = h["profile-update-interval"]?.trim()?.toIntOrNull(),
                name = h["content-disposition"]?.let(::fileName),
                webPageUrl = h["profile-web-page-url"]?.trim(),
            )
        }

        /**
         * `filename*=UTF-8''…` wins over `filename="…"`, as in RFC 6266; the extension is dropped.
         */
        fun fileName(contentDisposition: String): String? {
            val parts = contentDisposition.split(';').map { it.trim() }
            val extended =
                parts
                    .firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it.substringAfter("''"), Charsets.UTF_8) }
            val plain =
                parts
                    .firstOrNull { it.startsWith("filename=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.trim('"')
            return (extended ?: plain)?.substringBeforeLast('.')?.trim()?.ifEmpty { null }
        }
    }
}
