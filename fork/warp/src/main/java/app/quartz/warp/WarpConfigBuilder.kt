package app.quartz.warp

import app.quartz.warp.awg.AwgParams
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A WARP registration as returned by Cloudflare, plus the private key it was made with. */
data class WarpRegistration(
    val id: String,
    val token: String,
    val apiVersion: String,
    val privateKey: String,
    val peerPublicKey: String,
    val endpointHost: String,
    val addressV4: String,
    val addressV6: String?,
    val accountType: String?,
    /** Cloudflare's account TTL as epoch millis, when it sets one. */
    val ttlAt: Long?,
)

/**
 * The `# WGT-…` marker comments that tie a config to the generator (FORK.md §9). They go inside
 * `[Interface]`: upstream reads the first comment above it as the tunnel name and keeps
 * `[Interface]` comments on save.
 */
data class WarpMarkers(
    val slot: String,
    val variant: WarpVariant,
    val expiresAt: Long?,
) {
    fun lines(): List<String> = buildList {
        add("# $GENERATOR = warp")
        add("# $SLOT = $slot")
        add("# $VARIANT = ${variant.tag}")
        expiresAt?.let { add("# $EXPIRES = ${formatInstant(it)}") }
    }

    companion object {
        private const val GENERATOR = "WGT-Generator"
        private const val SLOT = "WGT-Slot"
        private const val VARIANT = "WGT-Variant"
        private const val EXPIRES = "WGT-Expires"
        private val LINE = Regex("^\\s*#\\s*(WGT-[A-Za-z]+)\\s*=\\s*(.*?)\\s*$")

        fun formatInstant(millis: Long): String =
            Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

        /** Markers in [config], or null if it wasn't made by the WARP generator. */
        fun parse(config: String): WarpMarkers? {
            val values =
                config
                    .lineSequence()
                    .mapNotNull { LINE.find(it) }
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            if (values[GENERATOR.lowercase()] != "warp") return null
            val slot =
                values[SLOT.lowercase()]?.takeIf { WarpOptions.isValidSlot(it) }
                    ?: WarpOptions.DEFAULT_SLOT
            val variant = WarpVariant.fromTag(values[VARIANT.lowercase()]) ?: return null
            val expires =
                values[EXPIRES.lowercase()]?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                }
            return WarpMarkers(slot, variant, expires)
        }

        /** [config] without any marker lines. */
        fun strip(config: String): String =
            config.lineSequence().filterNot { LINE.matches(it) }.joinToString("\n")

        /** [config] with its markers replaced by [markers], placed right after `[Interface]`. */
        fun write(config: String, markers: WarpMarkers): String {
            val lines = strip(config).lines().toMutableList()
            val at = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
            if (at < 0) return config
            lines.addAll(at + 1, markers.lines())
            return lines.joinToString("\n")
        }
    }
}

object WarpConfigBuilder {

    /** Effective expiry: the earlier of the user's choice and Cloudflare's TTL. */
    fun effectiveExpiry(chosen: Long?, ttlAt: Long?): Long? =
        listOfNotNull(chosen, ttlAt).minOrNull()

    fun build(
        registration: WarpRegistration,
        variant: WarpVariant,
        options: WarpOptions,
        endpoint: String,
        awg: AwgParams?,
        expiresAt: Long?,
    ): String = buildString {
        appendLine("[Interface]")
        WarpMarkers(options.slot, variant, expiresAt).lines().forEach(::appendLine)
        appendLine("PrivateKey = ${registration.privateKey}")
        val addresses =
            listOfNotNull("${registration.addressV4}/32", registration.addressV6?.let { "$it/128" })
        appendLine("Address = ${addresses.joinToString(", ")}")
        if (options.dns.isNotBlank()) appendLine("DNS = ${options.dns.trim()}")
        appendLine("MTU = ${options.mtu}")
        if (variant == WarpVariant.AWG) {
            requireNotNull(awg) { "AmneziaWG needs I1..I5" }
            appendLine("Jc = ${options.junk.jc}")
            appendLine("Jmin = ${options.junk.jMin}")
            appendLine("Jmax = ${options.junk.jMax}")
            // Locked: Cloudflare's end is stock WireGuard.
            appendLine("S1 = 0")
            appendLine("S2 = 0")
            appendLine("H1 = 1")
            appendLine("H2 = 2")
            appendLine("H3 = 3")
            appendLine("H4 = 4")
            appendLine("I1 = ${awg.i1}")
            appendLine("I2 = ${awg.i2}")
            appendLine("I3 = ${awg.i3}")
            appendLine("I4 = ${awg.i4}")
            appendLine("I5 = ${awg.i5}")
        }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${registration.peerPublicKey}")
        appendLine("AllowedIPs = ${options.allowedIps.trim()}")
        appendLine("Endpoint = $endpoint")
        options.keepalive?.takeIf { it > 0 }?.let { appendLine("PersistentKeepalive = $it") }
    }

    fun resolveEndpoint(choice: EndpointChoice, registration: WarpRegistration): String =
        when (choice) {
            EndpointChoice.Default -> registration.endpointHost
            EndpointChoice.Random -> WarpEndpoints.random()
            is EndpointChoice.Custom -> choice.endpoint.trim()
        }

    fun tunnelName(slot: String, variant: WarpVariant): String {
        val base = if (variant == WarpVariant.AWG) "WARP AWG" else "WARP"
        return if (slot == WarpOptions.DEFAULT_SLOT) base else "$base ($slot)"
    }
}
