package app.quartz.clash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.core.ip.IpLookup
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.ExpandingRowListItem
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow
import org.koin.compose.koinInject

/**
 * A Clash tunnel shown as a group on the Tunnels list, in the app's compact list row (the one its
 * reorder list uses): under the tunnel's row, each proxy group is a folder icon and its name; the
 * main group (the first one you pick a node in) starts open. Its nodes follow, not indented:
 * a dot for the selected one, the name with its protocol under it, latency on the right. Shown
 * whether or not the tunnel is connected; tap a node to select it while connected.
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
    // Groups the user opened or closed, relative to the default (main group open).
    var toggled by rememberSaveable(tunnelId) { mutableStateOf(emptySet<String>()) }
    val current = tree ?: return
    val groupNames = current.groupNames()
    val main = current.mainGroup

    Column {
        current.groups.forEach { group ->
            val open = (group.name == main) != (group.name in toggled)
            ExpandingRowListItem(
                leading = {
                    Icon(
                        if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = AnnotatedString(group.name),
                trailing = {
                    Icon(
                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription =
                            stringResource(if (open) R.string.collapse_group else R.string.expand_group),
                    )
                },
                isSelected = false,
                expanded = {},
                onClick = {
                    toggled = if (group.name in toggled) toggled - group.name else toggled + group.name
                },
            )
            if (open) {
                group.members.forEach { member ->
                    val isGroup = member in groupNames
                    val node = current.nodes[member]
                    ExpandingRowListItem(
                        leading = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Circle,
                                    contentDescription = null,
                                    tint =
                                        if (member == group.now) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Column {
                                    Text(
                                        member,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Text(
                                        when {
                                            isGroup -> stringResource(R.string.quartz_clash_group)
                                            node != null -> node.protocol
                                            else -> stringResource(R.string.quartz_clash_provider_node)
                                        },
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        },
                        text = AnnotatedString(""),
                        trailing = {
                            if (!isGroup) QuartzLatencyText(QuartzDelays.nodeKey(tunnelId, member))
                        },
                        isSelected = false,
                        expanded = {},
                        onClick = {
                            if (current.live && group.selectable)
                                trees.select(tunnelId, group.name, member) { ipLookup.invalidate() }
                        },
                    )
                }
            }
        }
    }
}

/** What goes under a Tunnels list row: a Clash tunnel's groups and nodes; nothing otherwise. */
@Composable
fun QuartzRowExtras(row: TunnelListRow) {
    val tunnel = (row as? TunnelListRow.TunnelRow)?.tunnel ?: return
    if (
        app.quartz.bridge.EnginePlaceholder.engineOf(tunnel.quickConfig) ==
            app.quartz.core.data.EngineProfile.ENGINE_MIHOMO
    )
        QuartzClashRows(tunnel.id)
}
