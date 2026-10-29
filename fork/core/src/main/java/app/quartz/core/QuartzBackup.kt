package app.quartz.core

import app.quartz.core.data.TunnelMeta
import app.quartz.core.data.TunnelMetaDao
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
    private val json = Json { ignoreUnknownKeys = true }
    private val metaDao: TunnelMetaDao by inject()

    suspend fun export(): Map<String, ByteArray> =
        mapOf(TUNNEL_META to json.encodeToString(metaDao.getAll()).encodeToByteArray())

    /**
     * Replaces Quartz's data with the backup's. A backup without it (from WG Tunnel, or an older
     * Quartz) clears it instead: its tunnel IDs belong to the old database. Tunnels that carry WARP
     * markers are then adopted again by [QuartzSync].
     */
    suspend fun restore(extras: Map<String, ByteArray>) {
        val metas =
            extras[TUNNEL_META]?.let { data ->
                runCatching { json.decodeFromString<List<TunnelMeta>>(data.decodeToString()) }
                    .onFailure { Timber.e(it, "Unreadable Quartz data in backup") }
                    .getOrNull()
            }
        metaDao.replaceAll(metas.orEmpty())
    }
}
