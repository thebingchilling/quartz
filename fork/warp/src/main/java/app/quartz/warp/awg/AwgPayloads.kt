package app.quartz.warp.awg

import kotlin.random.Random

/** AmneziaWG fields for one registration: picked once, shared by every config made from it. */
data class AwgParams(
    val i1: String,
    val i2: String,
    val i3: String,
    val i4: String,
    val i5: String,
)

/**
 * Picks I1..I5. I1 is a captured WARP QUIC Initial when [captured] has any (bundled as
 * `assets/warp/awg-payloads.txt`, one per line, from Skiro1/warp-awg-gen like the site's
 * `vendor/awg-payloads.js`); otherwise a freshly built [QuicInitial]. I2..I5 are each 1–3 random
 * byte blocks of 16–63 bytes.
 */
object AwgPayloads {
    const val ASSET = "warp/awg-payloads.txt"

    fun pick(
        captured: List<String>,
        mimicHost: String,
        random: Random = Random.Default,
    ): AwgParams {
        val i1 =
            captured.randomOrNull(random)?.let(::normalize)
                ?: tag(QuicInitial.build(mimicHost, random))
        fun junk() =
            (1..random.nextInt(1, 4)).joinToString("") {
                tag(random.nextBytes(random.nextInt(16, 64)))
            }
        return AwgParams(i1 = i1, i2 = junk(), i3 = junk(), i4 = junk(), i5 = junk())
    }

    /**
     * Lines in the asset may be bare hex or already `<b 0x…>` tags; blank and `#` lines are
     * skipped.
     */
    fun parse(text: String): List<String> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    private fun normalize(payload: String): String =
        if (payload.startsWith("<")) payload else "<b 0x${payload.removePrefix("0x").lowercase()}>"

    fun tag(bytes: ByteArray): String = "<b 0x${bytes.joinToString("") { "%02x".format(it) }}>"
}
