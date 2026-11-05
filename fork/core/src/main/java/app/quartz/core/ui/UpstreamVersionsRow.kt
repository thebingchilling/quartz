package app.quartz.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.quartz.core.updates.UpstreamVersion
import app.quartz.core.updates.UpstreamVersions
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import kotlinx.coroutines.launch

/**
 * "Check upstream versions" on the Info tab: compares this build's WG Tunnel, mihomo and OpenVPN
 * bases with their latest releases and, if any is newer, prompts to run `quartz-release` with
 * "update" on GitHub (the app can't start the workflow itself).
 */
@Composable
fun UpstreamVersionsRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpstreamVersions() }
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<UpstreamVersion>?>(null) }

    SurfaceRow(
        leading = { Icon(Icons.Outlined.Sync, contentDescription = null) },
        title = stringResource(R.string.quartz_upstream_check),
        description = { DescriptionText(stringResource(R.string.quartz_upstream_check_desc)) },
        trailing = { modifier -> if (checking) CircularProgressIndicator(modifier) },
        onClick = {
            if (checking) return@SurfaceRow
            checking = true
            scope.launch {
                results =
                    checker.check(
                        UpstreamVersions.Bases(
                            wgTunnel = BuildConfig.QUARTZ_ANDROID_BASE,
                            wgTunnelDesktop = BuildConfig.QUARTZ_DESKTOP_BASE,
                            mihomo = BuildConfig.QUARTZ_MIHOMO_VERSION,
                            openvpn3 = BuildConfig.QUARTZ_OPENVPN3_VERSION,
                        )
                    )
                checking = false
            }
        },
    )

    results?.let { list ->
        val updates = list.filter { it.updateAvailable }
        AlertDialog(
            onDismissRequest = { results = null },
            title = {
                Text(
                    stringResource(
                        if (updates.isEmpty()) R.string.quartz_upstream_up_to_date
                        else R.string.quartz_upstream_updates_available
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { VersionLine(it) }
                    if (updates.isNotEmpty()) {
                        Text(
                            stringResource(R.string.quartz_upstream_how_to_update),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                if (updates.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            context.openWebUrl(UpstreamVersions.UPDATE_WORKFLOW_URL)
                            results = null
                        }
                    ) {
                        Text(stringResource(R.string.quartz_upstream_update))
                    }
                } else {
                    TextButton(onClick = { results = null }) { Text(stringResource(R.string.okay)) }
                }
            },
            dismissButton =
                if (updates.isNotEmpty()) {
                    {
                        TextButton(onClick = { results = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else null,
        )
    }
}

@Composable
private fun VersionLine(version: UpstreamVersion) {
    val status =
        when {
            !version.included ->
                stringResource(R.string.quartz_upstream_not_included, version.latest ?: "?")
            version.error != null ->
                stringResource(R.string.quartz_upstream_failed, version.current)
            version.updateAvailable ->
                stringResource(R.string.quartz_upstream_newer, version.current, version.latest!!)
            else -> stringResource(R.string.quartz_upstream_current, version.current)
        }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(version.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color =
                when {
                    version.updateAvailable -> Straw
                    version.error != null || !version.included -> MaterialTheme.colorScheme.outline
                    else -> SilverTree
                },
        )
    }
}
