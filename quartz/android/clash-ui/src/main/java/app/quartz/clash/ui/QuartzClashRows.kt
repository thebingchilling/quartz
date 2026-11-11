package app.quartz.clash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.bridge.EnginePlaceholder
import app.quartz.core.data.EngineProfile
import app.quartz.core.ip.IpLookup
import app.quartz.core.ui.QuartzOutlineSegment
import app.quartz.core.ui.quartzOutline
import app.quartz.core.ui.quartzOutlineColor
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow
import org.koin.compose.koinInject

/**
 * A Clash tunnel's proxy groups under its row on the Tunnels list, as a compact tree that reads
 * as part of the tunnel (the row and its groups share one outline, [app.quartz.core.ui.quartzRowOutline]).
 *
 * Top-level groups are the ones no other group lists (usually just PROXY, which starts open).
 * A group another group lists is a sub-folder inside it: its row shows its type and current
 * choice ("Auto → HK 01"); tapping the row picks it in the parent (like a node), and its chevron
 * opens it to show its own members. Sub-folders are indented inside a faint outline. Nodes are one
 * line each: a dot for the selected one, name and protocol, latency on the right. Shown whether or
 * not the tunnel runs; picking only works while it runs (live from mihomo).
 */
@Composable
fun QuartzClashRows(
    tunnelId: Int,
    grouped: Boolean = false,
    trees: ClashTrees = koinInject(),
    ipLookup: IpLookup = koinInject(),
) {
    val treeFlow = remember(tunnelId) { trees.tree(tunnelId) }
    val tree by treeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(tunnelId) { trees.refresh(tunnelId) }
    // Folders the user opened or closed, by path, relative to the default (main group open).
    var toggled by rememberSaveable(tunnelId) { mutableStateOf(emptySet<String>()) }
    val current = tree ?: return
    val byName = current.groups.associateBy { it.name }
    val listed = current.groups.flatMap { it.members }.toSet()
    val roots =
        current.groups.filter { it.name !in listed }.ifEmpty {
            listOfNotNull(current.mainGroup?.let(byName::get))
        }
    val main = current.mainGroup

    val id = tunnelId
    val groupsByName = byName
    val scope =
        object : ClashTreeScope {
            override val tunnelId = id
            override val tree = current
            override val byName = groupsByName

            override fun isOpen(path: String, name: String): Boolean =
                (path == name && name == main) != (path in toggled)

            override fun toggle(path: String) {
                toggled = if (path in toggled) toggled - path else toggled + path
            }

            override fun select(group: ClashGroup, member: String) {
                if (current.live && group.selectable)
                    trees.select(tunnelId, group.name, member) { ipLookup.invalidate() }
            }
        }

    Column(Modifier.padding(start = if (grouped) 24.dp else 0.dp, bottom = 6.dp)) {
        roots.forEach { group -> scope.GroupTree(group, parent = null, path = group.name, setOf()) }
    }
}

private interface ClashTreeScope {
    val tunnelId: Int
    val tree: ClashTree
    val byName: Map<String, ClashGroup>

    fun isOpen(path: String, name: String): Boolean

    fun toggle(path: String)

    fun select(group: ClashGroup, member: String)
}

@Composable
private fun ClashTreeScope.GroupTree(
    group: ClashGroup,
    parent: ClashGroup?,
    path: String,
    ancestors: Set<String>,
) {
    val open = isOpen(path, group.name)
    GroupRow(
        group = group,
        open = open,
        selected = parent != null && parent.now == group.name,
        showDot = parent != null,
        onClick = { if (parent != null && parent.selectable && tree.live) select(parent, group.name) else toggle(path) },
        onToggle = { toggle(path) },
    )
    if (!open) return
    // Members inside the folder: indented, sub-folders inside a faint outline.
    Column(Modifier.padding(start = 14.dp)) {
        group.members.forEach { member ->
            val sub = byName[member]
            if (sub != null && member !in ancestors && member != group.name) {
                Box(
                    Modifier.padding(vertical = 2.dp)
                        .quartzOutline(
                            QuartzOutlineSegment.SINGLE,
                            quartzOutlineColor(),
                            start = 4.dp,
                            end = 10.dp,
                            gap = 0.dp,
                            radius = 10.dp,
                        )
                ) {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        GroupTree(sub, group, "$path/$member", ancestors + group.name)
                    }
                }
            } else {
                NodeRow(
                    name = member,
                    protocol =
                        if (sub != null) stringResource(R.string.quartz_clash_group)
                        else tree.nodes[member]?.protocol
                            ?: stringResource(R.string.quartz_clash_provider_node),
                    selected = member == (group.now ?: group.members.firstOrNull().takeIf { !tree.live }),
                    latencyKey = QuartzDelays.nodeKey(tunnelId, member).takeIf { sub == null },
                    onClick = { select(group, member) },
                )
            }
        }
    }
}

/** A group's current choice, followed through sub-groups to the node actually used. */
private fun ClashTreeScope.leafOf(group: ClashGroup, seen: Set<String> = emptySet()): String? {
    val now = group.now ?: group.members.firstOrNull().takeIf { !tree.live } ?: return null
    val sub = byName[now] ?: return now
    return if (now in seen) null else leafOf(sub, seen + group.name)
}

@Composable
private fun ClashTreeScope.GroupRow(
    group: ClashGroup,
    open: Boolean,
    selected: Boolean,
    showDot: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val leaf = leafOf(group)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Dot(selected)
            Spacer(Modifier.width(10.dp))
        }
        Icon(
            if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(groupType(group.type), group.now?.let { "→ $it" }).joinToString(" "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        leaf?.takeIf { tree.nodes[it]?.testable != false }?.let {
            QuartzLatencyText(QuartzDelays.nodeKey(tunnelId, it))
        }
        Box(
            Modifier.clickable(onClick = onToggle).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription =
                    stringResource(if (open) R.string.collapse_group else R.string.expand_group),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NodeRow(
    name: String,
    protocol: String,
    selected: Boolean,
    latencyKey: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(selected)
        Text(
            name,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            protocol,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.weight(0.001f))
        if (latencyKey != null) QuartzLatencyText(latencyKey)
    }
}

@Composable
private fun Dot(selected: Boolean) {
    Icon(
        Icons.Rounded.Circle,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.size(10.dp),
    )
}

@Composable
private fun groupType(type: String): String? =
    when (type.lowercase()) {
        "select",
        "selector" -> stringResource(R.string.quartz_clash_type_select)
        "url-test",
        "urltest" -> stringResource(R.string.quartz_clash_type_auto)
        "fallback" -> stringResource(R.string.quartz_clash_type_fallback)
        "load-balance",
        "loadbalance" -> stringResource(R.string.quartz_clash_type_balance)
        "relay" -> stringResource(R.string.quartz_clash_type_relay)
        else -> null
    }

/** What goes under a Tunnels list row: a Clash tunnel's groups and nodes; nothing otherwise. */
@Composable
fun QuartzRowExtras(row: TunnelListRow) {
    val tunnelRow = row as? TunnelListRow.TunnelRow ?: return
    if (EnginePlaceholder.engineOf(tunnelRow.tunnel.quickConfig) == EngineProfile.ENGINE_MIHOMO)
        QuartzClashRows(tunnelRow.tunnel.id, grouped = tunnelRow.grouped)
}
