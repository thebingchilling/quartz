package app.quartz.warp.awg

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicInitialTest {

    /** RFC 9001 Appendix A.1 client keys for DCID 8394c8f03e515708. */
    @Test
    fun initialKeysMatchRfc9001() {
        val keys = QuicInitial.clientKeys(hex("8394c8f03e515708"))
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", keys.key.hex())
        assertEquals("fa044b2f42a3fd3b46fb255c", keys.iv.hex())
        assertEquals("9f50449e04a0e810283a1e9933adedd2", keys.hp.hex())
    }

    /** Decrypts the packet the way a middlebox would and finds the ClientHello's SNI. */
    @Test
    fun packetDecryptsToClientHello() {
        val packet = QuicInitial.build("www.example.com", Random(7))
        assertTrue(packet.size >= 1200)
        assertEquals(0xc0, packet[0].toInt() and 0xf0 and 0xc0)
        assertEquals(1, packet[4].toInt()) // version 1

        val dcidLength = packet[5].toInt()
        val dcid = packet.copyOfRange(6, 6 + dcidLength)
        var offset = 6 + dcidLength
        assertEquals(0, packet[offset++].toInt()) // SCID length
        assertEquals(0, packet[offset++].toInt()) // token length
        val length =
            ((packet[offset].toInt() and 0x3f) shl 8) or (packet[offset + 1].toInt() and 0xff)
        offset += 2
        assertEquals(packet.size - offset, length)

        val keys = QuicInitial.clientKeys(dcid)
        val mask = QuicInitial.hpMask(keys.hp, packet.copyOfRange(offset + 4, offset + 20))
        val header = packet.copyOfRange(0, offset + 4)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        val pnLength = (header[0].toInt() and 0x03) + 1
        var packetNumber = 0
        for (i in 0 until pnLength) {
            header[offset + i] = (header[offset + i].toInt() xor mask[1 + i].toInt()).toByte()
            packetNumber = (packetNumber shl 8) or (header[offset + i].toInt() and 0xff)
        }
        val aad = header.copyOfRange(0, offset + pnLength)
        val nonce = keys.iv.copyOf()
        for (i in 0 until pnLength) {
            val shift = 8 * (pnLength - 1 - i)
            nonce[nonce.size - pnLength + i] =
                (nonce[nonce.size - pnLength + i].toInt() xor (packetNumber shr shift)).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keys.key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(aad)
        val payload = cipher.doFinal(packet.copyOfRange(offset + pnLength, packet.size))

        assertEquals(0x06, payload[0].toInt()) // CRYPTO frame
        assertEquals(0x01, payload[4].toInt()) // ClientHello (after offset 0 and 2-byte length)
        assertTrue(String(payload, Charsets.ISO_8859_1).contains("www.example.com"))
        assertTrue(String(payload, Charsets.ISO_8859_1).contains("h3"))
    }

    @Test
    fun awgPayloadsUseCapturedWhenAvailable() {
        val params = AwgPayloads.pick(listOf("0xc0ffee"), "www.google.com", Random(1))
        assertEquals("<b 0xc0ffee>", params.i1)
        listOf(params.i2, params.i3, params.i4, params.i5).forEach { junk ->
            val blocks =
                Regex("<b 0x([0-9a-f]+)>")
                    .findAll(junk)
                    .map { it.groupValues[1].length / 2 }
                    .toList()
            assertTrue(blocks.size in 1..3)
            assertTrue(blocks.all { it in 16..63 })
        }
        assertEquals(listOf("abc", "<b 0x01>"), AwgPayloads.parse("# comment\n\nabc\n <b 0x01> \n"))
    }

    private fun hex(s: String) =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
