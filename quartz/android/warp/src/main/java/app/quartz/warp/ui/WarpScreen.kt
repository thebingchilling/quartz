package app.quartz.warp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.warp.SlotTunnel
import app.quartz.warp.WarpVariant
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrOptions
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.androidx.compose.koinViewModel

/** The WARP generator: the site's configurator (thebingchilling/site tools/warp), plus slots. */
@Composable
fun WarpScreen(viewModel: WarpViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val warpTunnels by viewModel.warpTunnels.collectAsStateWithLifecycle()
    val form = state.form
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            VariantSwitch(form.variant) { v -> viewModel.update { it.copy(variant = v) } }
            DescriptionText(
                stringResource(
                    if (form.variant == WarpVariant.AWG) R.string.quartz_warp_hint_awg
                    else R.string.quartz_warp_hint_wg
                )
            )
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
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Button(onClick = viewModel::generate, enabled = !state.busy) {
                Icon(Icons.Outlined.Autorenew, null, modifier = Modifier.padding(end = 8.dp))
                Text(
                    stringResource(
                        if (state.generated == null) R.string.quartz_warp_generate
                        else R.string.quartz_warp_register_new
                    )
                )
            }
            if (state.busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
            Output(
                accountLines =
                    listOfNotNull(
                        stringResource(
                            R.string.quartz_warp_account_type,
                            generated.registration.accountType ?: "free",
                        ),
                        generated.registration.license?.let {
                            stringResource(R.string.quartz_warp_license_key, it)
                        },
                        generated.registration.createdAt?.let {
                            stringResource(R.string.quartz_warp_created, formatDate(it))
                        },
                        stringResource(
                            R.string.quartz_warp_expires_on,
                            formatDate(generated.expiresAt),
                        ),
                    ),
                config = config,
                variant = options.variant,
                slot = options.slot,
                replaces =
                    warpTunnels.any {
                        it.meta.warpSlot == options.slot && it.variant == options.variant
                    },
                busy = state.busy,
                onSave = viewModel::save,
                onDownload = viewModel::download,
            )
        }

        if (warpTunnels.isNotEmpty()) {
            Column {
                GroupLabel(
                    stringResource(R.string.quartz_warp_saved),
                    Modifier.padding(horizontal = 16.dp),
                )
                warpTunnels.forEach { SlotTunnelRow(it) { viewModel.detach(it.tunnel.id) } }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariantSwitch(selected: WarpVariant, onSelect: (WarpVariant) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        WarpVariant.entries.forEachIndexed { index, variant ->
            SegmentedButton(
                selected = variant == selected,
                onClick = { onSelect(variant) },
                shape = SegmentedButtonDefaults.itemShape(index, WarpVariant.entries.size),
            ) {
                Text(
                    stringResource(
                        if (variant == WarpVariant.AWG) R.string.quartz_warp_awg
                        else R.string.quartz_warp_wg
                    )
                )
            }
        }
    }
}

@Composable
private fun AdvancedOptions(form: WarpForm, viewModel: WarpViewModel) {
    val box = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DescriptionText(
            stringResource(R.string.quartz_warp_endpoint),
            Modifier.padding(horizontal = 16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            EndpointMode.entries.forEach { mode ->
                val label =
                    when (mode) {
                        EndpointMode.DEFAULT -> R.string.quartz_warp_endpoint_default
                        EndpointMode.RANDOM -> R.string.quartz_warp_endpoint_random
                        EndpointMode.CUSTOM -> R.string.quartz_warp_endpoint_custom
                    }
                if (mode == form.endpointMode) Button(onClick = {}) { Text(stringResource(label)) }
                else
                    OutlinedButton(onClick = { viewModel.setEndpointMode(mode) }) {
                        Text(stringResource(label))
                    }
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
        Field(stringResource(R.string.quartz_warp_slot), form.slot, box) { v ->
            viewModel.update { it.copy(slot = v) }
        }
        DescriptionText(
            stringResource(R.string.quartz_warp_slot_desc),
            Modifier.padding(horizontal = 16.dp),
        )
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
private fun Output(
    accountLines: List<String>,
    config: String,
    variant: WarpVariant,
    slot: String,
    replaces: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onDownload: () -> Unit,
) {
    val clipboard = rememberClipboardHelper()
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                stringResource(R.string.quartz_warp_account),
                style = MaterialTheme.typography.titleMedium,
            )
            accountLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (config.toByteArray().size <= WarpViewModel.QR_MAX_BYTES) {
                val painter =
                    rememberQrCodePainter(
                        data = config,
                        options =
                            QrOptions {
                                colors { dark = QrBrush.solid(Color.Black) }
                                errorCorrectionLevel = QrErrorCorrectionLevel.Low
                            },
                    )
                Image(
                    painter = painter,
                    contentDescription = stringResource(R.string.quartz_warp_qr),
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .size(240.dp)
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                )
                DescriptionText(
                    stringResource(
                        if (variant == WarpVariant.AWG) R.string.quartz_warp_scan_awg
                        else R.string.quartz_warp_scan_wg
                    ),
                    Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                DescriptionText(stringResource(R.string.quartz_warp_qr_too_long))
            }

            SelectionContainer {
                Text(
                    config,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 40,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.copy(config) }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quartz_warp_copy))
                }
                OutlinedButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, null, Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quartz_warp_download))
                }
            }
            Button(onClick = onSave, enabled = !busy) {
                Icon(Icons.Outlined.SaveAlt, null, Modifier.padding(end = 4.dp))
                Text(
                    stringResource(
                        if (replaces) R.string.quartz_warp_replace_in_slot
                        else R.string.quartz_warp_save_to_slot,
                        slot,
                    )
                )
            }
        }
    }
}

@Composable
private fun SlotTunnelRow(slotTunnel: SlotTunnel, onDetach: () -> Unit) {
    val meta = slotTunnel.meta
    val details =
        listOfNotNull(
                meta.warpSlot,
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
    SurfaceRow(
        title = slotTunnel.tunnel.name,
        description = { DescriptionText(details) },
        trailing = { modifier ->
            TextButton(onClick = onDetach, modifier = modifier) {
                Text(stringResource(R.string.quartz_warp_detach))
            }
        },
    )
}

private fun formatDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
