package app.quartz.clash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.engine.mihomo.MihomoMode
import app.quartz.engine.mihomo.MihomoProxy
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ClashScreen(
    tunnelId: Int,
    viewModel: ClashViewModel = koinViewModel(key = "clash-$tunnelId") { parametersOf(tunnelId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profile
            ?.takeIf { it.subscriptionUrl != null }
            ?.let { sub ->
                item {
                    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.quartz_clash_subscription),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val lines =
                                listOfNotNull(
                                    sub.lastUpdatedAt?.let {
                                        stringResource(
                                            R.string.quartz_clash_last_updated,
                                            formatDate(it),
                                        )
                                    },
                                    sub.totalBytes?.let {
                                        stringResource(
                                            R.string.quartz_import_usage,
                                            gib(sub.usedBytes ?: 0),
                                            gib(it),
                                        )
                                    },
                                    sub.subscriptionExpire?.let {
                                        stringResource(
                                            R.string.quartz_import_sub_expires,
                                            formatDate(it * 1000),
                                        )
                                    },
                                )
                            lines.forEach { DescriptionText(it) }
                            sub.lastError?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = viewModel::updateSubscription,
                                    enabled = !state.updatingSubscription,
                                ) {
                                    Icon(Icons.Outlined.Sync, null, Modifier.padding(end = 4.dp))
                                    Text(stringResource(R.string.quartz_clash_update_now))
                                }
                                if (state.updatingSubscription)
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                sub.webPageUrl?.let { url ->
                                    TextButton(onClick = { context.openWebUrl(url) }) {
                                        Text(stringResource(R.string.quartz_clash_web_page))
                                    }
                                }
                            }
                        }
                    }
                }
            }

        if (!state.running) {
            item {
                DescriptionText(
                    stringResource(R.string.quartz_clash_not_running),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            item {
                Column {
                    GroupLabel(
                        stringResource(R.string.quartz_clash_mode),
                        Modifier.padding(horizontal = 16.dp),
                    )
                    Chips(
                        MihomoMode.entries,
                        state.mode,
                        { it.value.replaceFirstChar(Char::uppercase) },
                        viewModel::setMode,
                    )
                }
            }
            item {
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
            }
            state.error?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            val proxies = state.proxies
            if (proxies == null) {
                item { CircularProgressIndicator(Modifier.padding(16.dp).size(28.dp)) }
            } else {
                val groups =
                    proxies.groups.filter {
                        !it.hidden && (it.name != "GLOBAL" || state.mode == MihomoMode.GLOBAL)
                    }
                items(groups, key = { it.name }) { group ->
                    GroupRow(
                        group = group,
                        members =
                            group.all
                                .mapNotNull { proxies.byName[it] }
                                .filter {
                                    state.query.isBlank() ||
                                        it.name.contains(state.query, ignoreCase = true)
                                }
                                .let { list ->
                                    if (state.sortByDelay)
                                        list.sortedBy { it.delay ?: Int.MAX_VALUE }
                                    else list
                                },
                        expanded = group.name in state.expanded || state.query.isNotBlank(),
                        testing = group.name in state.testing,
                        onToggle = { viewModel.toggle(group.name) },
                        onTest = { viewModel.testGroup(group.name) },
                        onSelect = { viewModel.select(group.name, it) },
                    )
                }
            }
        }

        item {
            Column {
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Settings, null) },
                    title = stringResource(R.string.quartz_clash_tunnel_settings),
                    description = {
                        DescriptionText(stringResource(R.string.quartz_clash_tunnel_settings_desc))
                    },
                    onClick = { navController.push(Route.TunnelSettings(tunnelId)) },
                )
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.FileDownload, null) },
                    title = stringResource(R.string.quartz_clash_export),
                    onClick = viewModel::exportProfile,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: MihomoProxy,
    members: List<MihomoProxy>,
    expanded: Boolean,
    testing: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit,
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
                Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                    if (testing) CircularProgressIndicator(Modifier.size(20.dp))
                    else
                        IconButton(onClick = onTest) {
                            Icon(Icons.Outlined.Bolt, stringResource(R.string.quartz_clash_test))
                        }
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
                    Text(
                        member.delay?.let { "$it ms" } ?: "—",
                        color = delayColor(member.delay),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
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
private fun <T> Chips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
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

private fun gib(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)

private fun formatDate(millis: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
