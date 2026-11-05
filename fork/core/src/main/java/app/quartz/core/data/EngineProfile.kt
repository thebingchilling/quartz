package app.quartz.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A tunnel run by another engine than WireGuard (FORK.md §3, data model). Upstream keeps a shell
 * TunnelConfig row with the same ID; the profile itself is a file in the app's storage. Optional
 * subscription fields live here too (FORK.md §7): one tunnel, at most one subscription.
 */
@Serializable
@Entity(tableName = "engine_profile")
data class EngineProfile(
    @PrimaryKey @ColumnInfo(name = "tunnel_id") val tunnelId: Int,
    /** [ENGINE_MIHOMO], later "openvpn". */
    @ColumnInfo(name = "engine") val engine: String,
    /** File name under the engine's profile directory. */
    @ColumnInfo(name = "profile_file") val profileFile: String,
    @ColumnInfo(name = "subscription_url") val subscriptionUrl: String? = null,
    @ColumnInfo(name = "user_agent") val userAgent: String? = null,
    /** How a proxies-only subscription becomes a profile: "simple", "rule:NZ" or "custom". */
    @ColumnInfo(name = "template") val template: String? = null,
    @ColumnInfo(name = "custom_template") val customTemplate: String? = null,
    @ColumnInfo(name = "update_interval_hours") val updateIntervalHours: Int? = null,
    @ColumnInfo(name = "wifi_only", defaultValue = "0") val wifiOnly: Boolean = false,
    @ColumnInfo(name = "via_vpn", defaultValue = "1") val viaVpn: Boolean = true,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "used_bytes") val usedBytes: Long? = null,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long? = null,
    /** Epoch seconds, from subscription-userinfo. */
    @ColumnInfo(name = "subscription_expire") val subscriptionExpire: Long? = null,
    @ColumnInfo(name = "web_page_url") val webPageUrl: String? = null,
) {
    companion object {
        const val ENGINE_MIHOMO = "mihomo"
    }
}

@Dao
interface EngineProfileDao {
    @Query("SELECT * FROM engine_profile") fun flow(): Flow<List<EngineProfile>>

    @Query("SELECT * FROM engine_profile") suspend fun getAll(): List<EngineProfile>

    @Query("SELECT * FROM engine_profile WHERE tunnel_id = :tunnelId")
    suspend fun get(tunnelId: Int): EngineProfile?

    @Upsert suspend fun upsert(profile: EngineProfile)

    @Query("DELETE FROM engine_profile WHERE tunnel_id IN (:tunnelIds)")
    suspend fun deleteAll(tunnelIds: Collection<Int>)
}
