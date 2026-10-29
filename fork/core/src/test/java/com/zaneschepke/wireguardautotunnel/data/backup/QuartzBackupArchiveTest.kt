package com.zaneschepke.wireguardautotunnel.data.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quartz's extra `quartz/…` entries in upstream's backup zip (fork hook in BackupArchive). */
class QuartzBackupArchiveTest {
    private val database =
        ByteArray(128).also {
            "SQLite format 3\u0000".encodeToByteArray().copyInto(it)
            ByteBuffer.wrap(it).putInt(60, 30)
        }
    private val manifest =
        BackupManifest(BackupArchive.PLATFORM_ANDROID, 30, "test", encrypted = false)
    private val extras = mapOf("tunnel_meta.json" to "[{\"tunnelId\":1}]".encodeToByteArray())

    private fun write(password: String?): ByteArray =
        ByteArrayOutputStream()
            .also { BackupArchive.write(it, database, manifest, password, extras) }
            .toByteArray()

    @Test
    fun extrasRoundTripPlain() {
        val decoded = BackupArchive.read(write(null), "backup.zip", null, 30) as DecodedBackup.Ok
        assertArrayEquals(database, decoded.database)
        assertArrayEquals(
            extras.getValue("tunnel_meta.json"),
            decoded.extras.getValue("tunnel_meta.json"),
        )
    }

    @Test
    fun extrasAreEncryptedWithThePassword() {
        val bytes = write("secret")
        val names =
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry?.name }.toList()
            }
        assertTrue("quartz/tunnel_meta.json.aes" in names)
        val decoded = BackupArchive.read(bytes, "backup.zip", "secret", 30) as DecodedBackup.Ok
        assertArrayEquals(
            extras.getValue("tunnel_meta.json"),
            decoded.extras.getValue("tunnel_meta.json"),
        )
        assertEquals(
            DecodedBackup.WrongPassword,
            BackupArchive.read(bytes, "backup.zip", "wrong", 30),
        )
    }

    @Test
    fun upstreamBackupsStillRead() {
        val bytes =
            ByteArrayOutputStream()
                .also { BackupArchive.write(it, database, manifest, null) }
                .toByteArray()
        val decoded = BackupArchive.read(bytes, "backup.zip", null, 30) as DecodedBackup.Ok
        assertTrue(decoded.extras.isEmpty())
    }
}
