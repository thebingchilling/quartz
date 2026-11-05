package app.quartz.core

import app.quartz.bridge.ClashProfiles
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import app.quartz.core.data.TunnelMeta
import app.quartz.core.data.TunnelMetaDao
import java.io.File
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Quartz's data in upstream's backup: extra `quartz/…` entries in the same zip, encrypted with the
 * same password as the database (FORK.md §3). Called from two lines in DatabaseBackup.
 */
object QuartzBackup : KoinComponent {
    private const val TUNNEL_META = "tunnel_meta.json"
    private const val ENGINE_PROFILES = "engine_profile.json"
    private const val CLASH_DIR = "clash/"
    private val json = Json { ignoreUnknownKeys = true }
    private val metaDao: TunnelMetaDao by inject()
    private val profileDao: EngineProfileDao by inject()
    private val clashProfiles: ClashProfiles by inject()

    suspend fun export(): Map<String, ByteArray> {
        val profiles = profileDao.getAll()
        return buildMap {
            put(TUNNEL_META, json.encodeToString(metaDao.getAll()).encodeToByteArray())
            put(ENGINE_PROFILES, json.encodeToString(profiles).encodeToByteArray())
            profiles.forEach { profile ->
                val file = clashProfiles.file(profile)
                if (file.isFile) put(CLASH_DIR + profile.profileFile, file.readBytes())
            }
        }
    }

    /**
     * Replaces Quartz's data with the backup's. A backup without it (from WG Tunnel, or an older
     * Quartz) clears it instead: its tunnel IDs belong to the old database. Tunnels that carry WARP
     * markers are then adopted again by [QuartzSync].
     */
    suspend fun restore(extras: Map<String, ByteArray>) {
        metaDao.replaceAll(decode<List<TunnelMeta>>(extras[TUNNEL_META]).orEmpty())

        val profiles = decode<List<EngineProfile>>(extras[ENGINE_PROFILES]).orEmpty()
        clashProfiles.directory.listFiles()?.forEach { it.delete() }
        clashProfiles.directory.mkdirs()
        extras
            .filterKeys { it.startsWith(CLASH_DIR) }
            .forEach { (name, bytes) ->
                val fileName = File(name.removePrefix(CLASH_DIR)).name // no path tricks
                File(clashProfiles.directory, fileName).writeBytes(bytes)
            }
        profileDao.deleteAll(profileDao.getAll().map { it.tunnelId })
        profiles.forEach { profileDao.upsert(it) }
    }

    private inline fun <reified T> decode(data: ByteArray?): T? = data?.let {
        runCatching { json.decodeFromString<T>(it.decodeToString()) }
            .onFailure { Timber.e(it, "Unreadable Quartz data in backup") }
            .getOrNull()
    }
}
