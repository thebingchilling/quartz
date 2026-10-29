package app.quartz.warp

import kotlin.random.Random

enum class WarpVariant(val tag: String) {
    WG("wg"),
    AWG("awg");

    companion object {
        fun fromTag(tag: String?): WarpVariant? = entries.firstOrNull { it.tag == tag }
    }
}

enum class WarpOutput(val variants: List<WarpVariant>) {
    WIREGUARD(listOf(WarpVariant.WG)),
    AMNEZIA(listOf(WarpVariant.AWG)),
    BOTH(listOf(WarpVariant.WG, WarpVariant.AWG)),
}

/**
 * AmneziaWG junk packets. Only client-side junk works against WARP: Cloudflare's end is stock
 * WireGuard, so S1/S2 and H1..H4 are locked (see [WarpConfigBuilder]).
 */
enum class JunkPreset(val jc: Int, val jMin: Int, val jMax: Int) {
    LIGHT(2, 20, 50),
    MEDIUM(4, 40, 70),
    HEAVY(8, 50, 150),
    CUSTOM(4, 40, 70),
}

data class Junk(val jc: Int, val jMin: Int, val jMax: Int) {
    val isValid: Boolean
        get() = jc in 0..128 && jMin in 0..1280 && jMax in 0..1280

    /** Jmin/Jmax in order, as the site does when they're entered the wrong way round. */
    fun ordered() = Junk(jc, minOf(jMin, jMax), maxOf(jMin, jMax))

    companion object {
        val DEFAULT = Junk(JunkPreset.MEDIUM.jc, JunkPreset.MEDIUM.jMin, JunkPreset.MEDIUM.jMax)
    }
}

sealed interface EndpointChoice {
    /** The endpoint Cloudflare returns at registration. */
    data object Default : EndpointChoice

    /** A random anycast IP and port from [WarpEndpoints], picked per registration. */
    data object Random : EndpointChoice

    data class Custom(val endpoint: String) : EndpointChoice
}

enum class ExpiryChoice(val days: Int?) {
    DAY_1(1),
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    CUSTOM(null),
    NEVER(null),
}

data class WarpOptions(
    val slot: String = DEFAULT_SLOT,
    val output: WarpOutput = WarpOutput.AMNEZIA,
    val junk: Junk = Junk.DEFAULT,
    val endpoint: EndpointChoice = EndpointChoice.Default,
    val mtu: Int = DEFAULT_MTU,
    val dns: String = DEFAULT_DNS,
    val allowedIps: String = DEFAULT_ALLOWED_IPS,
    val keepalive: Int? = null,
    val license: String? = null,
    /** Host the I1 QUIC Initial pretends to connect to; null picks one of [MIMIC_HOSTS]. */
    val mimicHost: String? = null,
    /** Epoch millis chosen by the user; the effective expiry is capped by Cloudflare's TTL. */
    val expiresAt: Long? = null,
    val groupId: Int? = null,
    val deleteOldRegistration: Boolean = true,
    val revokeOnExpiry: Boolean = false,
) {
    companion object {
        const val DEFAULT_SLOT = "warp"
        const val DEFAULT_MTU = 1280
        const val DEFAULT_DNS = "1.1.1.1, 1.0.0.1"
        const val DEFAULT_ALLOWED_IPS = "0.0.0.0/0, ::/0"
        const val DEFAULT_KEEPALIVE = 0
        val MIMIC_HOSTS =
            listOf(
                "www.google.com",
                "www.youtube.com",
                "www.gstatic.com",
                "www.cloudflare.com",
                "www.microsoft.com",
                "www.apple.com",
            )

        private val SLOT_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

        fun isValidSlot(slot: String) = SLOT_REGEX.matches(slot)
    }
}

object WarpEndpoints {
    const val DEFAULT_HOST = "engage.cloudflareclient.com:2408"

    /**
     * WARP anycast addresses and the UDP ports its servers answer on besides 2408 (a network that
     * blocks the default port often misses these). Same lists as the site's generator and
     * Skiro1/warp-awg-gen.
     */
    val IPS: List<String> =
        listOf(
                1,
                11,
                14,
                21,
                24,
                31,
                34,
                41,
                44,
                51,
                54,
                61,
                64,
                71,
                74,
                81,
                84,
                91,
                94,
                101,
                105,
                106,
                205,
                215,
            )
            .map { "162.159.192.$it" }

    val PORTS: List<Int> =
        listOf(
            854,
            859,
            864,
            878,
            880,
            890,
            891,
            894,
            903,
            908,
            928,
            934,
            939,
            942,
            943,
            945,
            946,
            955,
            968,
            987,
            988,
            1002,
            1010,
            1014,
            1018,
            1070,
            1074,
            1180,
            1387,
            1843,
            2371,
            2506,
            3138,
            3476,
            3581,
            3854,
            4177,
            4198,
            4233,
            5279,
            5956,
            7103,
            7152,
            7156,
            7281,
            7559,
            8319,
            8742,
            8854,
            8886,
        )

    fun random(random: Random = Random.Default): String =
        "${IPS.random(random)}:${PORTS.random(random)}"
}
