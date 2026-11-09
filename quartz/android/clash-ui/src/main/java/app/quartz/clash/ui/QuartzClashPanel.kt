package app.quartz.clash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.engine.mihomo.MihomoProxy
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.IconSurfaceButton
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import org.koin.androidx.compose.koinViewModel

/**
 * The running Clash tunnel's proxy groups, under the tunnel list on the Tunnels screen: each group
 * is a row like a tunnel group (a lone group starts open) with its nodes underneath, as a list of
 * rows or a grid of the app's selection cards. Sort by delay and list/grid sit in the section
 * header. Delays come from the Tunnels screen's delay test. Nothing shows while no Clash tunnel
 * runs. Built only from the app's own components; called from one line in TunnelList.kt.
 */
@Composable
fun QuartzClashPanel(viewModel: ClashControlsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.tunnelId == null || state.groups.isEmpty()) return

    Column {
        // Section header, as upstream's config editor draws its "Interface" header
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier.height(48.dp).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                GroupLabel(stringResource(R.string.quartz_clash))
            }
            Row {
                IconButton(onClick = viewModel::toggleSort) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Sort,
                        stringResource(R.string.sort_by_latency),
                        tint =
                            if (state.sortByDelay) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = viewModel::toggleGrid) {
                    Icon(
                        if (state.grid) Icons.AutoMirrored.Rounded.ViewList
                        else Icons.Rounded.GridView,
                        stringResource(
                            if (state.grid) R.string.quartz_clash_list else R.string.quartz_clash_grid
                        ),
                    )
                }
            }
        }
        state.groups.forEach { group ->
            val open = state.isOpen(group)
            SurfaceRow(
                leading = {
                    Icon(
                        if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                title = group.name,
                description =
                    group.now?.let { now ->
                        {
                            Text(
                                text = now,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                onClick = { viewModel.toggle(group.name) },
                trailing = { _ ->
                    Icon(
                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription =
                            stringResource(if (open) R.string.collapse_group else R.string.expand_group),
                    )
                },
            )
            if (open) {
                val members = state.members(group)
                val onSelect: (MihomoProxy) -> Unit = { node ->
                    if (group.isSelectable) viewModel.select(group.name, node.name)
                }
                if (state.grid) NodeGrid(members, group, state::delayOf, onSelect)
                else
                    members.forEach { node ->
                        val delay = state.delayOf(node)
                        val tested = node.name in state.results || node.delay != null
                        SurfaceRow(
                            modifier = Modifier.padding(start = 24.dp),
                            title = node.name,
                            description =
                                if (tested) {
                                    {
                                        Text(
                                            text = delayLabel(delay),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = delayColor(delay),
                                        )
                                    }
                                } else null,
                            selected = node.name == group.now,
                            onClick = { onSelect(node) },
                        )
                    }
            }
        }
    }
}

/** Nodes as the app's selection cards, two per row. */
@Composable
private fun NodeGrid(
    members: List<MihomoProxy>,
    group: MihomoProxy,
    delayOf: (MihomoProxy) -> Int?,
    onSelect: (MihomoProxy) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 40.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        members.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { node ->
                    Box(Modifier.weight(1f)) {
                        IconSurfaceButton(
                            title = node.name,
                            description = delayOf(node)?.let { "${it}ms" },
                            selected = node.name == group.now,
                            onClick = { onSelect(node) },
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// The Tunnels list's own latency label and colours (TunnelList.kt pingLatencyLabel/Color).
@Composable
private fun delayLabel(delay: Int?): String =
    delay?.let { "${it}ms" } ?: stringResource(R.string.ping_unreachable)

private fun delayColor(delay: Int?): Color =
    when {
        delay == null -> AlertRed
        delay <= 50 -> SilverTree
        delay <= 150 -> Straw
        else -> AlertRed
    }
