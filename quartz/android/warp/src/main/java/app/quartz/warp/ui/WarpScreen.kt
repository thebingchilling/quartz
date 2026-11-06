package app.quartz.warp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.warp.SlotTunnel
import app.quartz.warp.WarpVariant
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.IconSurfaceButton
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The WARP generator: the site's configurator (thebingchilling/site tools/warp) in the app's own
 * style. One WARP and one WARP AWG tunnel: saving replaces the previous one of that kind.
 */
@Composable
fun WarpScreen(viewModel: WarpViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val warpTunnels by viewModel.warpTunnels.collectAsStateWithLifecycle()
    val form = state.form
    val clipboard = rememberClipboardHelper()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            GroupLabel(stringResource(R.string.quartz_warp_type))
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
                title = stringResource(R.string.quartz_warp_advanced),
                onClick = { showAdvanced = !showAdvanced },
                trailing = { modifier ->
                    IconButton(onClick = { showAdvanced = !showAdvanced }, modifier = modifier) {
                        Icon(
                            if (showAdvanced) Icons.Outlined.ExpandLess
                            else Icons.Outlined.ExpandMore,
                            null,
                        )
                    }
                },
            )
            AnimatedVisibility(showAdvanced) { AdvancedOptions(form, viewModel) }
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Autorenew, null) },
                title =
                    stringResource(
                        if (state.generated == null) R.string.quartz_warp_generate
                        else R.string.quartz_warp_register_new
                    ),
                description = {
                    DescriptionText(stringResource(R.string.quartz_warp_generate_desc))
                },
                trailing = { modifier ->
                    if (state.busy) CircularProgressIndicator(modifier.size(24.dp))
                },
                onClick = { if (!state.busy) viewModel.generate() },
            )
        }

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        state.generated?.let { generated ->
            val options = form.toOptions()
            val config = remember(generated, options) { generated.config(options) }
            val existing = warpTunnels.firstOrNull { it.variant == options.variant }
            Column {
                GroupLabel(
                    stringResource(R.string.quartz_warp_account),
                    Modifier.padding(horizontal = 16.dp),
                )
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DescriptionText(
                        stringResource(
                            R.string.quartz_warp_account_type,
                            generated.registration.accountType ?: "free",
                        )
                    )
                    generated.registration.license?.let {
                        DescriptionText(stringResource(R.string.quartz_warp_license_key, it))
                    }
                    generated.registration.createdAt?.let {
                        DescriptionText(
                            stringResource(R.string.quartz_warp_created, formatDate(it))
                        )
                    }
                    DescriptionText(
                        stringResource(
                            R.string.quartz_warp_expires_on,
                            formatDate(generated.expiresAt),
                        )
                    )
                }
            }
            Column {
                GroupLabel(
                    stringResource(R.string.quartz_warp_config),
                    Modifier.padding(horizontal = 16.dp),
                )
                SelectionContainer(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        config,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.ContentCopy, null) },
                    title = stringResource(R.string.quartz_warp_copy),
                    onClick = { clipboard.copy(config) },
                )
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Download, null) },
                    title = stringResource(R.string.quartz_warp_download),
                    description = {
                        DescriptionText(
                            if (options.variant == WarpVariant.AWG) "warp-awg.conf" else "warp.conf"
                        )
                    },
                    onClick = viewModel::download,
                )
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.SaveAlt, null) },
                    title = stringResource(R.string.quartz_warp_save),
                    description = {
                        DescriptionText(
                            existing?.let {
                                stringResource(R.string.quartz_warp_save_replaces, it.tunnel.name)
                            }
                                ?: stringResource(
                                    R.string.quartz_warp_save_new,
                                    WarpViewModel.tunnelName(options),
                                )
                        )
                    },
                    onClick = { if (!state.busy) viewModel.save() },
                )
            }
        }

        if (warpTunnels.isNotEmpty()) {
            Column {
                GroupLabel(
                    stringResource(R.string.quartz_warp_saved),
                    Modifier.padding(horizontal = 16.dp),
                )
                warpTunnels.forEach { SlotTunnelRow(it) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AdvancedOptions(form: WarpForm, viewModel: WarpViewModel) {
    val box = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            DescriptionText(stringResource(R.string.quartz_warp_endpoint))
            EndpointMode.entries.forEach { mode ->
                IconSurfaceButton(
                    title =
                        stringResource(
                            when (mode) {
                                EndpointMode.DEFAULT -> R.string.quartz_warp_endpoint_default
                                EndpointMode.RANDOM -> R.string.quartz_warp_endpoint_random
                                EndpointMode.CUSTOM -> R.string.quartz_warp_endpoint_custom
                            }
                        ),
                    selected = form.endpointMode == mode,
                    onClick = { viewModel.setEndpointMode(mode) },
                )
            }
        }
        if (form.endpointMode != EndpointMode.DEFAULT) {
            ConfigurationTextBox(
                value = form.endpoint,
                label = stringResource(R.string.quartz_warp_endpoint_address),
                hint = "162.159.192.1:2408",
                onValueChange = viewModel::editEndpoint,
                trailing =
                    if (form.endpointMode == EndpointMode.RANDOM) {
                        { modifier ->
                            IconButton(onClick = viewModel::reroll, modifier = modifier) {
                                Icon(
                                    Icons.Outlined.Casino,
                                    stringResource(R.string.quartz_warp_reroll),
                                )
                            }
                        }
                    } else null,
                modifier = box,
            )
        }
        Field(stringResource(R.string.quartz_warp_dns), form.dns, box) { v ->
            viewModel.update { it.copy(dns = v) }
        }
        Field(stringResource(R.string.quartz_warp_mtu), form.mtu, box, number) { v ->
            viewModel.update { it.copy(mtu = v) }
        }
        Field(stringResource(R.string.quartz_warp_allowed_ips), form.allowedIps, box) { v ->
            viewModel.update { it.copy(allowedIps = v) }
        }
        Field(stringResource(R.string.quartz_warp_keepalive), form.keepalive, box, number) { v ->
            viewModel.update { it.copy(keepalive = v) }
        }
        Field(stringResource(R.string.quartz_warp_device_type), form.deviceType, box) { v ->
            viewModel.update { it.copy(deviceType = v) }
        }
        Field(stringResource(R.string.quartz_warp_locale), form.locale, box) { v ->
            viewModel.update { it.copy(locale = v) }
        }
        if (form.variant == WarpVariant.AWG) {
            Field(stringResource(R.string.quartz_warp_jc), form.jc, box, number) { v ->
                viewModel.update { it.copy(jc = v) }
            }
            Field(stringResource(R.string.quartz_warp_jmin), form.jMin, box, number) { v ->
                viewModel.update { it.copy(jMin = v) }
            }
            Field(stringResource(R.string.quartz_warp_jmax), form.jMax, box, number) { v ->
                viewModel.update { it.copy(jMax = v) }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onChange: (String) -> Unit,
) {
    ConfigurationTextBox(
        value = value,
        label = label,
        hint = "",
        keyboardOptions = keyboardOptions,
        onValueChange = onChange,
        modifier = modifier,
    )
}

@Composable
private fun SlotTunnelRow(slotTunnel: SlotTunnel) {
    val meta = slotTunnel.meta
    val details =
        listOfNotNull(
                slotTunnel.variant?.let {
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
    SurfaceRow(title = slotTunnel.tunnel.name, description = { DescriptionText(details) })
}

private fun formatDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
