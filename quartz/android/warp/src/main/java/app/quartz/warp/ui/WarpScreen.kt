package app.quartz.warp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.warp.SlotTunnel
import app.quartz.warp.WarpVariant
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.IconSurfaceButton
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The WARP generator, built only from the app's own components: pick WireGuard or AmneziaWG
 * (selection cards, as the Wi-Fi detection screen), optional advanced options (a switch row and
 * the config editor's text boxes), then Generate; once generated, the only action is Save to
 * tunnels, which replaces the WARP tunnel of that kind. Your WARP tunnels are listed last.
 */
@Composable
fun WarpScreen(viewModel: WarpViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val warpTunnels by viewModel.warpTunnels.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val form = state.form
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            WarpVariant.entries.forEach { variant ->
                IconSurfaceButton(
                    title =
                        stringResource(
                            if (variant == WarpVariant.AWG) R.string.quartz_warp_awg
                            else R.string.quartz_warp_wg
                        ),
                    description =
                        stringResource(
                            if (variant == WarpVariant.AWG) R.string.quartz_warp_hint_awg
                            else R.string.quartz_warp_hint_wg
                        ),
                    selected = form.variant == variant,
                    onClick = { viewModel.update { it.copy(variant = variant) } },
                )
            }
        }

        Column {
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                title = stringResource(R.string.quartz_warp_advanced),
                trailing = { modifier ->
                    ThemedSwitch(
                        checked = showAdvanced,
                        onClick = { showAdvanced = it },
                        modifier = modifier,
                    )
                },
                onClick = { showAdvanced = !showAdvanced },
            )
            if (showAdvanced) AdvancedOptions(form, viewModel)

            val existing = warpTunnels.firstOrNull { it.variant == form.variant }
            val name = existing?.tunnel?.name ?: WarpViewModel.tunnelName(form.toOptions())
            when {
                state.generated == null ->
                    SurfaceRow(
                        leading = { Icon(Icons.Outlined.Autorenew, contentDescription = null) },
                        title = stringResource(R.string.quartz_warp_generate),
                        description = {
                            DescriptionText(stringResource(R.string.quartz_warp_generate_desc))
                        },
                        trailing = {
                            ActionButton(stringResource(R.string.quartz_warp_generate_button)) {
                                viewModel.generate()
                            }
                        },
                        onClick = viewModel::generate,
                        enabled = !state.busy,
                    )
                state.saved ->
                    // Applied: the visual indicator instead of a toast
                    SurfaceRow(
                        leading = {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        title = stringResource(R.string.quartz_warp_applied),
                        description = {
                            DescriptionText(stringResource(R.string.quartz_warp_applied_desc, name))
                        },
                    )
                else ->
                    SurfaceRow(
                        leading = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                        title = stringResource(R.string.quartz_warp_save),
                        description = {
                            DescriptionText(
                                if (existing != null)
                                    stringResource(R.string.quartz_warp_save_replaces, name)
                                else stringResource(R.string.quartz_warp_save_new, name)
                            )
                        },
                        trailing = {
                            ActionButton(stringResource(R.string.save)) { viewModel.save() }
                        },
                        onClick = viewModel::save,
                        enabled = !state.busy,
                    )
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (state.generated != null) {
            Column {
                Box(
                    modifier = Modifier.height(48.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    GroupLabel(stringResource(R.string.quartz_warp_profile))
                }
                viewModel.parameters().forEach { (key, value) ->
                    SurfaceRow(
                        title = key,
                        description = {
                            DescriptionText(
                                if (value.length > 64) value.take(64) + "…" else value
                            )
                        },
                    )
                }
            }
        }

        if (warpTunnels.isNotEmpty()) {
            Column {
                Box(
                    modifier = Modifier.height(48.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    GroupLabel(stringResource(R.string.quartz_warp_saved))
                }
                warpTunnels.forEach { warp ->
                    WarpTunnelRow(warp) {
                        navController.push(Route.TunnelSettings(warp.tunnel.id))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** The auto-tunnel screen's row button. */
@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Button(onClick) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun AdvancedOptions(form: WarpForm, viewModel: WarpViewModel) {
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ConfigurationTextBox(
            value = form.endpoint,
            label = stringResource(R.string.endpoint),
            hint = stringResource(R.string._default),
            onValueChange = { v -> viewModel.update { it.copy(endpoint = v) } },
            trailing = { modifier ->
                IconButton(onClick = viewModel::reroll, modifier = modifier) {
                    Icon(Icons.Outlined.Casino, stringResource(R.string.quartz_warp_reroll))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Field(stringResource(R.string.dns_servers), form.dns) { v ->
            viewModel.update { it.copy(dns = v) }
        }
        Field(stringResource(R.string.mtu), form.mtu, number) { v ->
            viewModel.update { it.copy(mtu = v) }
        }
        Field(stringResource(R.string.allowed_ips), form.allowedIps) { v ->
            viewModel.update { it.copy(allowedIps = v) }
        }
        Field(stringResource(R.string.persistent_keepalive), form.keepalive, number) { v ->
            viewModel.update { it.copy(keepalive = v) }
        }
        Field(stringResource(R.string.quartz_warp_device_type), form.deviceType) { v ->
            viewModel.update { it.copy(deviceType = v) }
        }
        Field(stringResource(R.string.quartz_warp_locale), form.locale) { v ->
            viewModel.update { it.copy(locale = v) }
        }
        if (form.variant == WarpVariant.AWG) {
            Field(stringResource(R.string.jc), form.jc, number) { v ->
                viewModel.update { it.copy(jc = v) }
            }
            Field(stringResource(R.string.jmin), form.jMin, number) { v ->
                viewModel.update { it.copy(jMin = v) }
            }
            Field(stringResource(R.string.jmax), form.jMax, number) { v ->
                viewModel.update { it.copy(jMax = v) }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onChange: (String) -> Unit,
) {
    ConfigurationTextBox(
        value = value,
        label = label,
        hint = "",
        keyboardOptions = keyboardOptions,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WarpTunnelRow(warp: SlotTunnel, onClick: () -> Unit) {
    val meta = warp.meta
    val details =
        listOfNotNull(
                warp.variant?.let {
                    stringResource(
                        if (it == WarpVariant.AWG) R.string.quartz_warp_awg
                        else R.string.quartz_warp_wg
                    )
                },
                meta.expiresAt?.let {
                    if (meta.isExpired()) stringResource(R.string.quartz_expired)
                    else stringResource(R.string.quartz_warp_expires_on, formatDate(it))
                },
            )
            .joinToString(" · ")
    SurfaceRow(
        title = warp.tunnel.name,
        description = { DescriptionText(details) },
        onClick = onClick,
    )
}

private fun formatDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
