package app.quartz.clash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quartz.bridge.EnginePlaceholder
import app.quartz.core.data.EngineProfile
import app.quartz.core.ip.IpLookup
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.koinInject

/**
 * The Tunnels list as flat entries: upstream's rows, and under each Clash tunnel its proxy groups
 * and nodes, each its own lazy list item (a profile can have hundreds of nodes: drawn in one item
 * they made the screen lag). [stack] is the cards the entry sits in, outermost first
 * ([app.quartz.core.ui.quartzCardLayers]): an upstream group, a Clash tunnel, its open groups.
 */
sealed interface QuartzListEntry {
    val key: String
    val stack: List<String>

    data class Row(val row: TunnelListRow, val index: Int, override val stack: List<String>) :
        QuartzListEntry {
        override val key: String
            get() = row.key
    }

    /** A Clash proxy group: [parent] lists it (null for a top-level group). */
    data class ClashHeader(
        override val key: String,
        override val stack: List<String>,
        val tunnelId: Int,
        val group: ClashGroup,
        val parent: ClashGroup?,
        val path: String,
        val open: Boolean,
        val leaf: String?,
        val live: Boolean,
    ) : QuartzListEntry

    /** A node (or a built-in like DIRECT, or a provider's node) in [group]. */
    data class ClashNode(
        override val key: String,
        override val stack: List<String>,
        val tunnelId: Int,
        val group: ClashGroup,
        val name: String,
        val protocol: String?,
        val testable: Boolean,
        val selected: Boolean,
        val divider: Boolean,
    ) : QuartzListEntry
}

/** Which Clash groups are open, by tunnel and path, relative to the default (main group open). */
object QuartzClashExpanded {
    val toggled = MutableStateFlow(emptySet<String>())

    fun toggle(id: String) {
        toggled.value = toggled.value.let { if (id in it) it - id else it + id }
    }
}

private const val SEP = "\u001F"

/**
 * [rows] with every Clash tunnel's groups and nodes after its row. Trees load once (not again on
 * every scroll); they reload on connect/disconnect, refresh and node switches.
 */
@Composable
fun rememberQuartzListEntries(
    rows: List<TunnelListRow>,
    trees: ClashTrees = koinInject(),
): List<QuartzListEntry> {
    val clashIds =
        remember(rows) {
            rows.mapNotNull { row ->
                (row as? TunnelListRow.TunnelRow)
                    ?.tunnel
                    ?.takeIf { EnginePlaceholder.engineOf(it.quickConfig) == EngineProfile.ENGINE_MIHOMO }
                    ?.id
            }
        }
    LaunchedEffect(clashIds) { clashIds.forEach(trees::ensure) }
    val treesFlow =
        remember(clashIds) {
            if (clashIds.isEmpty()) flowOf(emptyMap<Int, ClashTree?>())
            else
                combine(clashIds.map { id -> trees.tree(id) }) { values ->
                    clashIds.zip(values.toList()).toMap()
                }
        }
    val loaded by treesFlow.collectAsState(initial = emptyMap<Int, ClashTree?>())
    val toggled by QuartzClashExpanded.toggled.collectAsState()
    return remember(rows, loaded, toggled) { buildEntries(rows, loaded, toggled) }
}

internal fun buildEntries(
    rows: List<TunnelListRow>,
    trees: Map<Int, ClashTree?>,
    toggled: Set<String>,
): List<QuartzListEntry> = buildList {
    rows.forEachIndexed { index, row ->
        val stack =
            when (row) {
                is TunnelListRow.GroupHeader -> listOf("f:${row.group.id}")
                is TunnelListRow.TunnelRow -> {
                    val folder = row.tunnel.groupId?.takeIf { row.grouped }?.let { "f:$it" }
                    val clash =
                        "c:${row.tunnel.id}".takeIf {
                            EnginePlaceholder.engineOf(row.tunnel.quickConfig) ==
                                EngineProfile.ENGINE_MIHOMO
                        }
                    listOfNotNull(folder, clash)
                }
            }
        add(QuartzListEntry.Row(row, index, stack))
        val tunnel = (row as? TunnelListRow.TunnelRow)?.tunnel ?: return@forEachIndexed
        val tree = trees[tunnel.id] ?: return@forEachIndexed
        clashEntries(tunnel.id, tree, toggled, stack)
    }
}

private fun MutableList<QuartzListEntry>.clashEntries(
    tunnelId: Int,
    tree: ClashTree,
    toggled: Set<String>,
    stack: List<String>,
) {
    val byName = tree.groups.associateBy { it.name }
    val listed = tree.groups.flatMap { it.members }.toSet()
    val roots =
        tree.groups.filter { it.name !in listed }.ifEmpty {
            listOfNotNull(tree.mainGroup?.let(byName::get))
        }
    val main = tree.mainGroup

    fun leafOf(group: ClashGroup, seen: Set<String> = emptySet()): String? {
        val now = group.now ?: return null
        val sub = byName[now] ?: return now
        return if (now in seen) null else leafOf(sub, seen + group.name)
    }

    fun group(g: ClashGroup, parent: ClashGroup?, path: String, ancestors: Set<String>, outer: List<String>) {
        val id = "$tunnelId$SEP$path"
        val open = (parent == null && g.name == main) != (id in toggled)
        val mine = outer + "g:$id"
        add(
            QuartzListEntry.ClashHeader(
                key = "clash-h-$id",
                stack = mine,
                tunnelId = tunnelId,
                group = g,
                parent = parent,
                path = id,
                open = open,
                leaf = leafOf(g),
                live = tree.live,
            )
        )
        if (!open) return
        var afterNode = false
        g.members.forEachIndexed { i, member ->
            val sub = byName[member]
            if (sub != null && member !in ancestors && member != g.name) {
                group(sub, g, "$path$SEP$member", ancestors + g.name, mine)
                afterNode = false
            } else {
                val node = tree.nodes[member]
                add(
                    QuartzListEntry.ClashNode(
                        key = "clash-n-$id$SEP$i",
                        stack = mine,
                        tunnelId = tunnelId,
                        group = g,
                        name = member,
                        protocol = node?.protocol,
                        testable = sub == null && node?.testable != false,
                        selected = member == g.now,
                        divider = afterNode,
                    )
                )
                afterNode = true
            }
        }
    }

    roots.forEach { group(it, null, it.name, emptySet(), stack) }
}

/** One Clash entry of the Tunnels list. */
@Composable
fun QuartzClashEntry(
    entry: QuartzListEntry,
    trees: ClashTrees = koinInject(),
    ipLookup: IpLookup = koinInject(),
) {
    val select = { group: ClashGroup, member: String ->
        if (group.selectable) trees.select(entry.tunnelIdOrZero(), group.name, member) { ipLookup.invalidate() }
    }
    when (entry) {
        is QuartzListEntry.ClashHeader -> ClashGroupRow(entry, select)
        is QuartzListEntry.ClashNode -> ClashNodeRow(entry, select)
        is QuartzListEntry.Row -> Unit
    }
}

private fun QuartzListEntry.tunnelIdOrZero(): Int =
    when (this) {
        is QuartzListEntry.ClashHeader -> tunnelId
        is QuartzListEntry.ClashNode -> tunnelId
        is QuartzListEntry.Row -> 0
    }

/**
 * A proxy group's header, the top of its card: folder, name, its type and what it uses now (with
 * that node's latency), and a chevron; tapping it opens or closes the group. A group inside
 * another has a checkbox to use it there (e.g. Auto inside PROXY).
 */
@Composable
private fun ClashGroupRow(entry: QuartzListEntry.ClashHeader, select: (ClashGroup, String) -> Unit) {
    val group = entry.group
    val parent = entry.parent
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { QuartzClashExpanded.toggle(entry.path) }
                .padding(start = if (parent != null) 4.dp else 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parent != null) {
            Checkbox(
                checked = parent.now == group.name,
                onCheckedChange = { select(parent, group.name) },
                enabled = parent.selectable,
            )
        }
        Icon(
            if (entry.open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                group.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                            groupType(group.type),
                            pluralStringResource(R.plurals.quartz_clash_members, group.members.size, group.members.size),
                            group.now,
                        )
                        .joinToString(" · "),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.leaf?.let { leaf ->
                    LatencySuffix(QuartzDelays.nodeKey(entry.tunnelId, leaf))
                }
            }
        }
        Icon(
            if (entry.open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription =
                stringResource(if (entry.open) R.string.collapse_group else R.string.expand_group),
            modifier = Modifier.padding(8.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A node: its own divided row, easy to tap, with a radio button showing the one in use, its name,
 * and under it its type and latency. Tapping picks it (live while connected; otherwise it's what
 * the next connect uses). Groups mihomo picks for itself (Auto, Fallback) show their choice but
 * can't be changed.
 */
@Composable
private fun ClashNodeRow(entry: QuartzListEntry.ClashNode, select: (ClashGroup, String) -> Unit) {
    val canSelect = entry.group.selectable
    Column {
        if (entry.divider)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .then(
                        if (entry.selected)
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                        else Modifier
                    )
                    .clickable(enabled = canSelect) { select(entry.group, entry.name) }
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = entry.selected,
                onClick = if (canSelect) ({ select(entry.group, entry.name) }) else null,
                enabled = canSelect || entry.selected,
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (entry.selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.protocol ?: stringResource(R.string.quartz_clash_provider_node),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.testable) LatencySuffix(QuartzDelays.nodeKey(entry.tunnelId, entry.name))
                }
            }
        }
    }
}

/** " · 120ms" in the latency colours, once tested. */
@Composable
private fun LatencySuffix(key: String, delays: QuartzDelays = koinInject()) {
    val state by delays.state.collectAsState()
    if (key !in state.results) return
    Text(
        " · ",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    QuartzLatencyText(key, delays = delays)
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
