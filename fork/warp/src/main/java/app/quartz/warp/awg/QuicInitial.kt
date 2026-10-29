package app.quartz.warp.awg

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Builds a QUIC v1 client Initial packet (RFC 9000, 9001) carrying a TLS 1.3 ClientHello for [sni],
 * protected with the real Initial keys. Used as AmneziaWG's I1 when no captured payloads are
 * bundled: a middlebox that decrypts the Initial (anyone can, the keys come from the connection ID)
 * sees an ordinary HTTP/3 ClientHello.
 */
object QuicInitial {
    private val INITIAL_SALT = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
    private const val MIN_DATAGRAM = 1200

    data class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    fun build(sni: String, random: Random = Random.Default): ByteArray {
        val dcid = random.nextBytes(8)
        val clientHello = clientHello(sni, random)
        val crypto =
            ByteArrayOutputStream()
                .apply {
                    write(0x06) // CRYPTO
                    writeVarint(0)
                    writeVarint(clientHello.size.toLong())
                    write(clientHello)
                }
                .toByteArray()

        val pnLength = 2
        val packetNumber = random.nextInt(0, 0x3fff)
        // Header + payload + tag must reach MIN_DATAGRAM; pad the payload with PADDING frames.
        val headerLength = 1 + 4 + 1 + dcid.size + 1 + 1 + 2 + pnLength
        val payloadLength = maxOf(crypto.size, MIN_DATAGRAM - headerLength - 16)
        val payload = crypto.copyOf(payloadLength)

        val header =
            ByteArrayOutputStream()
                .apply {
                    write(0xc0 or (pnLength - 1)) // long header, fixed bit, Initial, PN length
                    write(byteArrayOf(0, 0, 0, 1)) // QUIC v1
                    write(dcid.size)
                    write(dcid)
                    write(0) // empty source connection ID
                    writeVarint(0) // no token
                    val length = pnLength + payload.size + 16
                    write(0x40 or (length shr 8)) // two-byte varint
                    write(length and 0xff)
                    write(packetNumber shr 8)
                    write(packetNumber and 0xff)
                }
                .toByteArray()

        val keys = clientKeys(dcid)
        val nonce = keys.iv.copyOf()
        for (i in 0 until pnLength) {
            val shift = 8 * (pnLength - 1 - i)
            nonce[nonce.size - pnLength + i] =
                (nonce[nonce.size - pnLength + i].toInt() xor (packetNumber shr shift)).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keys.key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(payload)

        // Header protection: sample starts 4 bytes after the start of the packet number.
        val pnOffset = header.size - pnLength
        val sample = sealed.copyOfRange(4 - pnLength, 4 - pnLength + 16)
        val mask = hpMask(keys.hp, sample)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLength) {
            header[pnOffset + i] = (header[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        return header + sealed
    }

    fun clientKeys(dcid: ByteArray): Keys {
        val initialSecret = hmac(INITIAL_SALT, dcid)
        val clientSecret = expandLabel(initialSecret, "client in", 32)
        return Keys(
            key = expandLabel(clientSecret, "quic key", 16),
            iv = expandLabel(clientSecret, "quic iv", 12),
            hp = expandLabel(clientSecret, "quic hp", 16),
        )
    }

    fun hpMask(hp: ByteArray, sample: ByteArray): ByteArray {
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
        return aes.doFinal(sample)
    }

    private fun clientHello(sni: String, random: Random): ByteArray {
        val extensions =
            ByteArrayOutputStream()
                .apply {
                    val host = sni.toByteArray(Charsets.US_ASCII)
                    extension(0x0000) { // server_name
                        u16(host.size + 3)
                        write(0)
                        u16(host.size)
                        write(host)
                    }
                    extension(0x000a) {
                        u16(4)
                        u16(0x001d)
                        u16(0x0017)
                    } // supported_groups
                    extension(0x000d) { // signature_algorithms
                        val algs =
                            intArrayOf(
                                0x0403,
                                0x0804,
                                0x0401,
                                0x0503,
                                0x0805,
                                0x0501,
                                0x0806,
                                0x0601,
                            )
                        u16(algs.size * 2)
                        algs.forEach { u16(it) }
                    }
                    extension(0x0010) {
                        u16(3)
                        write(2)
                        write("h3".toByteArray())
                    } // ALPN
                    extension(0x002b) {
                        write(2)
                        u16(0x0304)
                    } // supported_versions: TLS 1.3
                    extension(0x002d) {
                        write(1)
                        write(1)
                    } // psk_key_exchange_modes: psk_dhe_ke
                    extension(0x0033) { // key_share: x25519
                        u16(36)
                        u16(0x001d)
                        u16(32)
                        write(random.nextBytes(32))
                    }
                    extension(0x0039) { // quic_transport_parameters
                        param(0x01, 30_000) // max_idle_timeout
                        param(0x03, 1_472) // max_udp_payload_size
                        param(0x04, 15_728_640) // initial_max_data
                        param(0x05, 6_291_456) // initial_max_stream_data_bidi_local
                        param(0x06, 6_291_456) // initial_max_stream_data_bidi_remote
                        param(0x07, 6_291_456) // initial_max_stream_data_uni
                        param(0x08, 100) // initial_max_streams_bidi
                        param(0x09, 103) // initial_max_streams_uni
                        writeVarint(0x0f) // initial_source_connection_id: empty
                        writeVarint(0)
                    }
                }
                .toByteArray()

        val body =
            ByteArrayOutputStream()
                .apply {
                    u16(0x0303)
                    write(random.nextBytes(32))
                    write(0) // empty legacy_session_id (QUIC)
                    u16(6)
                    u16(0x1301)
                    u16(0x1302)
                    u16(0x1303)
                    write(1)
                    write(0) // null compression
                    u16(extensions.size)
                    write(extensions)
                }
                .toByteArray()

        return ByteArrayOutputStream()
            .apply {
                write(0x01) // ClientHello
                write(body.size shr 16)
                write((body.size shr 8) and 0xff)
                write(body.size and 0xff)
                write(body)
            }
            .toByteArray()
    }

    private fun ByteArrayOutputStream.extension(
        type: Int,
        content: ByteArrayOutputStream.() -> Unit,
    ) {
        val data = ByteArrayOutputStream().apply(content).toByteArray()
        u16(type)
        u16(data.size)
        write(data)
    }

    private fun ByteArrayOutputStream.param(id: Int, value: Long) {
        val encoded = ByteArrayOutputStream().apply { writeVarint(value) }.toByteArray()
        writeVarint(id.toLong())
        writeVarint(encoded.size.toLong())
        write(encoded)
    }

    private fun ByteArrayOutputStream.param(id: Int, value: Int) = param(id, value.toLong())

    private fun ByteArrayOutputStream.u16(value: Int) {
        write((value shr 8) and 0xff)
        write(value and 0xff)
    }

    internal fun ByteArrayOutputStream.writeVarint(value: Long) {
        when {
            value < 0x40 -> write(value.toInt())
            value < 0x4000 -> {
                write(0x40 or (value shr 8).toInt())
                write((value and 0xff).toInt())
            }
            value < 0x40000000 ->
                for (i in 3 downTo 0) {
                    val b = ((value shr (8 * i)) and 0xff).toInt()
                    write(if (i == 3) 0x80 or b else b)
                }
            else ->
                for (i in 7 downTo 0) {
                    val b = ((value shr (8 * i)) and 0xff).toInt()
                    write(if (i == 7) 0xc0 or b else b)
                }
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-Expand-Label (RFC 8446 §7.1) with an empty context; [length] <= 32. */
    private fun expandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val fullLabel = "tls13 $label".toByteArray(Charsets.US_ASCII)
        val info =
            ByteArrayOutputStream()
                .apply {
                    u16(length)
                    write(fullLabel.size)
                    write(fullLabel)
                    write(0)
                }
                .toByteArray()
        return hmac(secret, info + byteArrayOf(1)).copyOf(length)
    }

    private fun hex(s: String) =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
