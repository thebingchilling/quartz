package app.quartz.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Fork data for one upstream tunnel (FORK.md §3, data model). */
@Serializable
@Entity(tableName = "tunnel_meta")
data class TunnelMeta(
    @PrimaryKey @ColumnInfo(name = "tunnel_id") val tunnelId: Int,
    /** What made this tunnel, e.g. [SOURCE_WARP]; null for plain imports. */
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "warp_slot") val warpSlot: String? = null,
    /** "wg" or "awg". */
    @ColumnInfo(name = "warp_variant") val warpVariant: String? = null,
    /** Epoch milliseconds; null means never. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "warp_reg_id") val warpRegId: String? = null,
    @ColumnInfo(name = "warp_token") val warpToken: String? = null,
    /** WARP client API version the registration was made with, e.g. "v0a737". */
    @ColumnInfo(name = "warp_api_version") val warpApiVersion: String? = null,
    @ColumnInfo(name = "revoke_on_expiry") val revokeOnExpiry: Boolean = false,
    @ColumnInfo(name = "warned_expiring") val warnedExpiring: Boolean = false,
    @ColumnInfo(name = "handled_expiry") val handledExpiry: Boolean = false,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= now

    companion object {
        const val SOURCE_WARP = "warp"
    }
}
