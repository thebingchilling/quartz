package app.quartz.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Quartz's own data, kept out of upstream's AppDatabase so upstream's migration numbers never clash
 * with ours. Rows are keyed by upstream's tunnel ID; there is no foreign key across the two
 * databases, so [app.quartz.core.QuartzSync] removes rows whose tunnel is gone.
 */
@Database(entities = [TunnelMeta::class], version = 1, exportSchema = true)
abstract class ForkDatabase : RoomDatabase() {
    abstract fun tunnelMetaDao(): TunnelMetaDao

    companion object {
        const val NAME = "quartz.db"
    }
}

@Dao
interface TunnelMetaDao {
    @Query("SELECT * FROM tunnel_meta") fun flow(): Flow<List<TunnelMeta>>

    @Query("SELECT * FROM tunnel_meta") suspend fun getAll(): List<TunnelMeta>

    @Query("SELECT * FROM tunnel_meta WHERE tunnel_id = :tunnelId")
    suspend fun get(tunnelId: Int): TunnelMeta?

    @Query("SELECT * FROM tunnel_meta WHERE warp_slot = :slot")
    suspend fun getBySlot(slot: String): List<TunnelMeta>

    @Upsert suspend fun upsert(meta: TunnelMeta)

    @Query("DELETE FROM tunnel_meta WHERE tunnel_id = :tunnelId") suspend fun delete(tunnelId: Int)

    @Query("DELETE FROM tunnel_meta WHERE tunnel_id IN (:tunnelIds)")
    suspend fun deleteAll(tunnelIds: Collection<Int>)

    @Query("DELETE FROM tunnel_meta") suspend fun clear()

    @Transaction
    suspend fun replaceAll(metas: List<TunnelMeta>) {
        clear()
        metas.forEach { upsert(it) }
    }
}
