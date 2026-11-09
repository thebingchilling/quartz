package app.quartz.core.ui

import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelGroup
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelGroupRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow

/**
 * Hold-and-drag reordering on the Tunnels list (FORK.md §11), with WG Tunnel Desktop's own row
 * logic (desktop/composeApp …/ui/state/TunnelListRows.kt `moveDisplayedRows`), ported to the
 * Android model types: tunnels move within the top level or a group, or into/out of a group;
 * collapsed groups move as one row.
 */
class QuartzTunnelOrder(
    private val tunnelRepository: TunnelRepository,
    private val groupRepository: TunnelGroupRepository,
) {
    /** Saves the order a drag produced; only rows whose position or group changed are written. */
    suspend fun save(groups: List<TunnelGroup>, tunnels: List<TunnelConfig>) {
        val savedGroups = groupRepository.getAll().associateBy { it.id }
        val changedGroups = groups.filter { savedGroups[it.id]?.position != it.position }
        if (changedGroups.isNotEmpty()) groupRepository.saveAll(changedGroups)
        val savedTunnels = tunnelRepository.getAll().associateBy { it.id }
        val changedTunnels =
            tunnels.filter { tunnel ->
                val saved = savedTunnels[tunnel.id] ?: return@filter false
                saved.position != tunnel.position || saved.groupId != tunnel.groupId
            }
        if (changedTunnels.isNotEmpty()) tunnelRepository.updateAll(changedTunnels)
    }

    companion object {
        fun moveDisplayedRows(
            groups: List<TunnelGroup>,
            tunnels: List<TunnelConfig>,
            fromIndex: Int,
            toIndex: Int,
            rows: List<TunnelListRow>,
        ): Pair<List<TunnelGroup>, List<TunnelConfig>>? {
            if (fromIndex == toIndex) return groups to tunnels
            if (fromIndex !in rows.indices || toIndex !in rows.indices) return null
            val fromRow = rows[fromIndex]
            val toRow = rows[toIndex]
            if (fromRow.scopeKey != toRow.scopeKey) {
                return moveTunnelAcrossScopes(
                    groups,
                    tunnels,
                    fromRow,
                    toRow,
                    movingDown = fromIndex < toIndex,
                )
            }
            val scopeGroupId =
                when (fromRow) {
                    is TunnelListRow.TunnelRow -> fromRow.tunnel.groupId
                    is TunnelListRow.GroupHeader -> null
                }
            if (scopeGroupId != null) {
                val children =
                    tunnels.filter { it.groupId == scopeGroupId }.sortedBy { it.position }
                val fromChild =
                    children.indexOfFirst {
                        it.id == (fromRow as TunnelListRow.TunnelRow).tunnel.id
                    }
                val toChild =
                    children.indexOfFirst { it.id == (toRow as TunnelListRow.TunnelRow).tunnel.id }
                if (fromChild < 0 || toChild < 0) return null
                return groups to moveGroupChildren(tunnels, scopeGroupId, fromChild, toChild)
            }
            val root = rootItems(groups, tunnels).toMutableList()
            val fromRoot = rootIndex(root, fromRow)
            val toRoot = rootIndex(root, toRow)
            if (fromRoot < 0 || toRoot < 0) return null
            val moved = root.removeAt(fromRoot)
            root.add(toRoot, moved)
            return reindexRoot(root, tunnels)
        }

        private fun moveTunnelAcrossScopes(
            groups: List<TunnelGroup>,
            tunnels: List<TunnelConfig>,
            fromRow: TunnelListRow,
            toRow: TunnelListRow,
            movingDown: Boolean,
        ): Pair<List<TunnelGroup>, List<TunnelConfig>>? {
            val moved =
                (fromRow as? TunnelListRow.TunnelRow)?.tunnel?.let { row ->
                    tunnels.firstOrNull { it.id == row.id }
                } ?: return null
            val others = tunnels.filter { it.id != moved.id }

            val targetGroupId = (toRow as? TunnelListRow.TunnelRow)?.tunnel?.groupId
            if (targetGroupId != null) {
                val target =
                    others
                        .filter { it.groupId == targetGroupId }
                        .sortedBy { it.position }
                        .toMutableList()
                val toIndex =
                    target.indexOfFirst { it.id == (toRow as TunnelListRow.TunnelRow).tunnel.id }
                if (toIndex < 0) return null
                // Entering from above lands before the row, from below after it
                target.add(
                    (toIndex + if (movingDown) 0 else 1).coerceIn(0, target.size),
                    moved.copy(groupId = targetGroupId),
                )
                val rest = others.filter { it.groupId != targetGroupId }
                return normalize(
                    groups,
                    rest + target.mapIndexed { index, tunnel -> tunnel.copy(position = index) },
                )
            }

            // Leaving its group for the top level, next to the row it was dragged onto
            val root = rootItems(groups, others).toMutableList()
            val toRoot = rootIndex(root, toRow)
            if (toRoot < 0) return null
            root.add(toRoot + if (movingDown) 1 else 0, RootItem.Tunnel(moved.copy(groupId = null)))
            val (newGroups, newTunnels) = reindexRoot(root, others)
            return normalizeChildren(newGroups, newTunnels)
        }

        private fun normalize(
            groups: List<TunnelGroup>,
            tunnels: List<TunnelConfig>,
        ): Pair<List<TunnelGroup>, List<TunnelConfig>> {
            val (newGroups, newTunnels) = reindexRoot(rootItems(groups, tunnels), tunnels)
            return normalizeChildren(newGroups, newTunnels)
        }

        private fun normalizeChildren(
            groups: List<TunnelGroup>,
            tunnels: List<TunnelConfig>,
        ): Pair<List<TunnelGroup>, List<TunnelConfig>> {
            val children =
                tunnels
                    .filter { it.groupId != null }
                    .groupBy { it.groupId }
                    .values
                    .flatMap { list ->
                        list
                            .sortedBy { it.position }
                            .mapIndexed { index, tunnel -> tunnel.copy(position = index) }
                    }
            return groups to (tunnels.filter { it.groupId == null } + children)
        }

        private sealed interface RootItem {
            val position: Int
            val name: String

            data class Group(val group: TunnelGroup) : RootItem {
                override val position: Int
                    get() = group.position

                override val name: String
                    get() = group.name
            }

            data class Tunnel(val tunnel: TunnelConfig) : RootItem {
                override val position: Int
                    get() = tunnel.position

                override val name: String
                    get() = tunnel.name
            }
        }

        private fun rootItems(
            groups: List<TunnelGroup>,
            tunnels: List<TunnelConfig>,
        ): List<RootItem> {
            val ungrouped = tunnels.filter { it.groupId == null }
            return (groups.map { RootItem.Group(it) } + ungrouped.map { RootItem.Tunnel(it) })
                .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
        }

        private fun rootIndex(root: List<RootItem>, row: TunnelListRow): Int =
            when (row) {
                is TunnelListRow.GroupHeader ->
                    root.indexOfFirst { it is RootItem.Group && it.group.id == row.group.id }
                is TunnelListRow.TunnelRow ->
                    root.indexOfFirst { it is RootItem.Tunnel && it.tunnel.id == row.tunnel.id }
            }

        private fun moveGroupChildren(
            tunnels: List<TunnelConfig>,
            groupId: Int,
            fromIndex: Int,
            toIndex: Int,
        ): List<TunnelConfig> {
            val children =
                tunnels.filter { it.groupId == groupId }.sortedBy { it.position }.toMutableList()
            val others = tunnels.filter { it.groupId != groupId }
            if (fromIndex !in children.indices || toIndex !in children.indices) return tunnels
            val moved = children.removeAt(fromIndex)
            children.add(toIndex, moved)
            return others + children.mapIndexed { index, tunnel -> tunnel.copy(position = index) }
        }

        private fun reindexRoot(
            root: List<RootItem>,
            tunnels: List<TunnelConfig>,
        ): Pair<List<TunnelGroup>, List<TunnelConfig>> {
            val grouped = tunnels.filter { it.groupId != null }
            val newGroups = mutableListOf<TunnelGroup>()
            val newUngrouped = mutableListOf<TunnelConfig>()
            root.forEachIndexed { index, item ->
                when (item) {
                    is RootItem.Group -> newGroups += item.group.copy(position = index)
                    is RootItem.Tunnel -> newUngrouped += item.tunnel.copy(position = index)
                }
            }
            return newGroups to (grouped + newUngrouped)
        }
    }
}
