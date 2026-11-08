package app.quartz.clash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.engine.mihomo.MihomoMode
import app.quartz.engine.mihomo.MihomoProxy
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import org.koin.androidx.compose.koinViewModel

/**
 * The running Clash tunnel's controls, right on the Tunnels screen under the tunnel list: mode,
 * one delay test for every node, search/sort, and every group as a drop-down of its nodes. Draws
 * nothing while no Clash tunnel runs. Called from one line in upstream's TunnelList.kt.
 */
@Composable
fun QuartzClashPanel(
    modifier: Modifier = Modifier,
    viewModel: ClashControlsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    if (state.tunnelId == null) return

    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        GroupLabel(
            listOf(stringResource(R.string.quartz_clash), name)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            Modifier.padding(horizontal = 16.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        ) {
            Chips(
                MihomoMode.entries,
                state.mode,
                { it.value.replaceFirstChar(Char::uppercase) },
                viewModel::setMode,
                Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::testAll, enabled = !state.testing) {
                if (state.testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "${state.tested}/${state.toTest}",
                        Modifier.padding(start = 6.dp),
                    )
                } else {
                    Icon(Icons.Outlined.Bolt, null, Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quartz_clash_test_all))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            ConfigurationTextBox(
                value = state.query,
                label = stringResource(R.string.quartz_clash_search),
                hint = "",
                onValueChange = viewModel::setQuery,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::toggleSort) {
                Icon(
                    Icons.Outlined.Sort,
                    stringResource(R.string.quartz_clash_sort),
                    tint =
                        if (state.sortByDelay) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                )
            }
        }
        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val proxies = state.proxies
        if (proxies == null) {
            CircularProgressIndicator(Modifier.padding(16.dp).size(28.dp))
            return@Column
        }
        proxies.groups
            .filter { !it.hidden && (it.name != "GLOBAL" || state.mode == MihomoMode.GLOBAL) }
            .forEach { group ->
                val members =
                    group.all
                        .mapNotNull { proxies.byName[it] }
                        .filter {
                            state.query.isBlank() || it.name.contains(state.query, ignoreCase = true)
                        }
                        .let { list ->
                            if (state.sortByDelay)
                                list.sortedBy { state.delayOf(it) ?: Int.MAX_VALUE }
                            else list
                        }
                if (state.query.isNotBlank() && members.isEmpty()) return@forEach
                GroupRow(
                    group = group,
                    members = members,
                    delayOf = state::delayOf,
                    selectedDelay = group.now?.let { proxies.byName[it] }?.let(state::delayOf),
                    expanded = group.name in state.expanded || state.query.isNotBlank(),
                    onToggle = { viewModel.toggle(group.name) },
                    onSelect = { viewModel.select(group.name, it) },
                )
            }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun GroupRow(
    group: MihomoProxy,
    members: List<MihomoProxy>,
    delayOf: (MihomoProxy) -> Int?,
    selectedDelay: Int?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column {
        SurfaceRow(
            title = group.name,
            description = {
                DescriptionText(listOfNotNull(group.type, group.now).joinToString(" · "))
            },
            onClick = onToggle,
            trailing = { modifier ->
                Row(
                    modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (group.now != null) DelayText(selectedDelay)
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null,
                    )
                }
            },
        )
        if (expanded) {
            members.forEach { member ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .then(
                                if (group.isSelectable) Modifier.clickable { onSelect(member.name) }
                                else Modifier
                            )
                            .padding(start = 40.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    if (member.name == group.now) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    } else Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(member.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            member.type,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    DelayText(delayOf(member))
                }
            }
        }
    }
}

@Composable
private fun DelayText(delay: Int?) {
    Text(
        delay?.let { "$it ms" } ?: "—",
        color = delayColor(delay),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** FORK.md §5 badge colours. */
private fun delayColor(delay: Int?): Color =
    when {
        delay == null -> Color.Gray
        delay < 300 -> SilverTree
        delay < 800 -> Straw
        else -> AlertRed
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
