package app.quartz.clash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.core.ip.IpLookup
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import org.koin.compose.koinInject

/**
 * A Clash tunnel shown as a group on the Tunnels list: right under its row, each proxy group is a
 * row like a tunnel group (open by default, tap to close) with its nodes underneath as rows like
 * tunnels: name, protocol under it, latency on the right, a dot marking the selected node. Tap a
 * node to select it while the tunnel runs. Called from TunnelList.kt under the Clash tunnel's row.
 */
@Composable
fun QuartzClashRows(
    tunnelId: Int,
    trees: ClashTrees = koinInject(),
    ipLookup: IpLookup = koinInject(),
) {
    val treeFlow = remember(tunnelId) { trees.tree(tunnelId) }
    val tree by treeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(tunnelId) { trees.refresh(tunnelId) }
    var closed by rememberSaveable(tunnelId) { mutableStateOf(emptySet<String>()) }
    val current = tree ?: return
    val groupNames = current.groupNames()

    Column {
        current.groups.forEach { group ->
            val open = group.name !in closed
            SurfaceRow(
                modifier = Modifier.padding(start = 24.dp),
                leading = {
                    Icon(
                        if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                title = group.name,
                description = {
                    Text(
                        text = group.now ?: protocolName(group.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { closed = if (open) closed + group.name else closed - group.name },
                trailing = { _ ->
                    Icon(
                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription =
                            stringResource(if (open) R.string.collapse_group else R.string.expand_group),
                    )
                },
            )
            if (open) {
                group.members.forEach { member ->
                    val isGroup = member in groupNames
                    val node = current.nodes[member]
                    val selected = member == group.now
                    SurfaceRow(
                        modifier = Modifier.padding(start = 48.dp),
                        leading = {
                            Icon(
                                Icons.Rounded.Circle,
                                contentDescription = null,
                                tint =
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        title = member,
                        description = {
                            Text(
                                text =
                                    when {
                                        isGroup -> stringResource(R.string.quartz_clash_group)
                                        node != null -> node.protocol
                                        else -> stringResource(R.string.quartz_clash_provider_node)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        },
                        onClick = {
                            if (current.live && group.selectable)
                                trees.select(tunnelId, group.name, member) { ipLookup.invalidate() }
                        },
                        trailing = { modifier ->
                            if (!isGroup)
                                QuartzLatencyText(
                                    QuartzDelays.nodeKey(tunnelId, member),
                                    modifier.padding(end = 16.dp),
                                )
                        },
                    )
                }
            }
        }
    }
}

/** What goes under a Tunnels list row: a Clash tunnel's groups and nodes; nothing otherwise. */
@Composable
fun QuartzRowExtras(row: com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow) {
    val tunnel =
        (row as? com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow.TunnelRow)?.tunnel
            ?: return
    if (app.quartz.bridge.EnginePlaceholder.engineOf(tunnel.quickConfig) ==
            app.quartz.core.data.EngineProfile.ENGINE_MIHOMO
    )
        QuartzClashRows(tunnel.id)
}
