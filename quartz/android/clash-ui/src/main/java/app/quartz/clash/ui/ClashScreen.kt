package app.quartz.clash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
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

        item {
            DescriptionText(
                stringResource(
                    if (state.running) R.string.quartz_clash_controls_on_tunnels
                    else R.string.quartz_clash_not_running
                ),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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

private fun gib(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)

private fun formatDate(millis: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
