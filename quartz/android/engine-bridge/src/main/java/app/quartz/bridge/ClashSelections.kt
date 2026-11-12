package app.quartz.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * The node picked in each Clash select group, per tunnel: picked on the Tunnels list whether or
 * not the tunnel runs, and applied to mihomo when it starts (before the reachability check), so a
 * choice made while disconnected is the one that connects.
 */
class ClashSelections(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Bumped on every change, for the UI to re-read. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Group name → picked member, for [tunnelId]. */
    fun get(tunnelId: Int): Map<String, String> {
        val text = prefs.getString(tunnelId.toString(), null) ?: return emptyMap()
        return runCatching {
                val json = JSONObject(text)
                json.keys().asSequence().associateWith { json.getString(it) }
            }
            .getOrDefault(emptyMap())
    }

    fun set(tunnelId: Int, group: String, member: String) {
        val next = get(tunnelId) + (group to member)
        prefs.edit().putString(tunnelId.toString(), JSONObject(next).toString()).apply()
        _version.update { it + 1 }
    }

    fun remove(tunnelId: Int) {
        prefs.edit().remove(tunnelId.toString()).apply()
        _version.update { it + 1 }
    }

    private companion object {
        const val PREFS = "quartz_clash_selections"
    }
}
