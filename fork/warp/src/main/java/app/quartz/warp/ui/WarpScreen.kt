package app.quartz.warp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.warp.ExpiryChoice
import app.quartz.warp.GeneratedWarp
import app.quartz.warp.JunkPreset
import app.quartz.warp.SlotTunnel
import app.quartz.warp.WarpConfigBuilder
import app.quartz.warp.WarpOutput
import app.quartz.warp.WarpVariant
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.settings.config.components.QrCodeDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.androidx.compose.koinViewModel

@Composable
fun WarpScreen(viewModel: WarpViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val warpTunnels by viewModel.warpTunnels.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val form = state.form
    val slot = form.slot.trim().lowercase()
    val inSlot = warpTunnels.filter { it.meta.warpSlot == slot }
    val otherSlots = warpTunnels.mapNotNull { it.meta.warpSlot }.distinct().filter { it != slot }

    var qrConfig by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    qrConfig?.let { (name, config) ->
        QrCodeDialog(TunnelConfig(name = name, quickConfig = config)) { qrConfig = null }
    }
    if (showDatePicker) {
        ExpiryDatePicker(
            initial = form.customExpiry,
            onDismiss = { showDatePicker = false },
            onPicked = { millis ->
                showDatePicker = false
                viewModel.update { it.copy(customExpiry = millis) }
            },
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        Section(stringResource(R.string.quartz_warp_slot)) {
            ConfigurationTextBox(
                value = form.slot,
                label = stringResource(R.string.quartz_warp_slot),
                hint = stringResource(R.string.quartz_warp_slot_hint),
                isError = WarpField.SLOT in state.errors,
                onValueChange = { value -> viewModel.update { it.copy(slot = value) } },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            )
            if (otherSlots.isNotEmpty()) {
                Chips(otherSlots, selected = null, label = { it }) { picked ->
                    viewModel.update { it.copy(slot = picked) }
                }
            }
            if (inSlot.isEmpty()) {
                DescriptionText(
                    stringResource(R.string.quartz_warp_slot_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            inSlot.forEach { SlotTunnelRow(it) { viewModel.detach(it.tunnel.id) } }
        }

        Section(stringResource(R.string.quartz_warp_output)) {
            Chips(WarpOutput.entries, form.output, label = { stringResource(it.labelRes()) }) {
                picked ->
                viewModel.update { it.copy(output = picked) }
            }
            if (WarpVariant.AWG in form.output.variants) {
                DescriptionText(
                    stringResource(R.string.quartz_warp_junk),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Chips(
                    JunkPreset.entries,
                    form.junkPreset,
                    label = { stringResource(it.labelRes()) },
                    onSelect = viewModel::setJunkPreset,
                )
                if (form.junkPreset == JunkPreset.CUSTOM) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        val error = WarpField.JUNK in state.errors
                        NumberBox("Jc", form.jc, error, Modifier.weight(1f)) { v ->
                            viewModel.update { it.copy(jc = v) }
                        }
                        NumberBox("Jmin", form.jMin, error, Modifier.weight(1f)) { v ->
                            viewModel.update { it.copy(jMin = v) }
                        }
                        NumberBox("Jmax", form.jMax, error, Modifier.weight(1f)) { v ->
                            viewModel.update { it.copy(jMax = v) }
                        }
                    }
                }
                DescriptionText(
                    stringResource(R.string.quartz_warp_awg_locked),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        Section(stringResource(R.string.quartz_warp_expiry)) {
            Chips(ExpiryChoice.entries, form.expiry, label = { stringResource(it.labelRes()) }) {
                picked ->
                viewModel.update { it.copy(expiry = picked) }
                if (picked == ExpiryChoice.CUSTOM) showDatePicker = true
            }
            if (form.expiry == ExpiryChoice.CUSTOM) {
                TextButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        form.customExpiry?.let(::formatDate)
                            ?: stringResource(R.string.quartz_warp_pick_date),
                        color =
                            if (WarpField.EXPIRY in state.errors) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            DescriptionText(
                stringResource(R.string.quartz_warp_expiry_desc),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Section(stringResource(R.string.quartz_warp_endpoint)) {
            Chips(
                EndpointMode.entries,
                form.endpointMode,
                label = { stringResource(it.labelRes()) },
            ) { picked ->
                viewModel.update { it.copy(endpointMode = picked) }
            }
            if (form.endpointMode == EndpointMode.CUSTOM) {
                ConfigurationTextBox(
                    value = form.customEndpoint,
                    label = stringResource(R.string.quartz_warp_endpoint),
                    hint = "162.159.192.1:2408",
                    isError = WarpField.ENDPOINT in state.errors,
                    onValueChange = { value ->
                        viewModel.update { it.copy(customEndpoint = value) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
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
            AnimatedVisibility(showAdvanced) {
                AdvancedOptions(form, state.errors, groups.map { it.id to it.name }, viewModel)
            }
        }

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Button(onClick = viewModel::generate, enabled = !state.busy) {
                Icon(Icons.Outlined.CloudSync, null, modifier = Modifier.padding(end = 8.dp))
                Text(
                    stringResource(
                        if (inSlot.isEmpty()) R.string.quartz_warp_generate
                        else R.string.quartz_warp_regenerate
                    )
                )
            }
            if (state.busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }

        state.generated?.let { generated ->
            GeneratedCard(
                generated = generated,
                replaces = inSlot.count { it.variant in generated.configs.keys },
                busy = state.busy,
                onSave = viewModel::save,
                onDiscard = viewModel::discard,
                onExport = viewModel::export,
                onQr = { variant ->
                    qrConfig =
                        WarpConfigBuilder.tunnelName(generated.options.slot, variant) to
                            generated.configs.getValue(variant)
                },
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AdvancedOptions(
    form: WarpForm,
    errors: Set<WarpField>,
    groups: List<Pair<Int, String>>,
    viewModel: WarpViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val boxModifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        ConfigurationTextBox(
            value = form.mtu,
            label = "MTU",
            hint = "1280",
            isError = WarpField.MTU in errors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onValueChange = { v -> viewModel.update { it.copy(mtu = v) } },
            modifier = boxModifier,
        )
        ConfigurationTextBox(
            value = form.dns,
            label = "DNS",
            hint = "1.1.1.1, 1.0.0.1",
            onValueChange = { v -> viewModel.update { it.copy(dns = v) } },
            modifier = boxModifier,
        )
        ConfigurationTextBox(
            value = form.allowedIps,
            label = "AllowedIPs",
            hint = "0.0.0.0/0, ::/0",
            isError = WarpField.ALLOWED_IPS in errors,
            onValueChange = { v -> viewModel.update { it.copy(allowedIps = v) } },
            modifier = boxModifier,
        )
        ConfigurationTextBox(
            value = form.keepalive,
            label = "PersistentKeepalive",
            hint = "25",
            isError = WarpField.KEEPALIVE in errors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onValueChange = { v -> viewModel.update { it.copy(keepalive = v) } },
            modifier = boxModifier,
        )
        ConfigurationTextBox(
            value = form.license,
            label = stringResource(R.string.quartz_warp_license),
            hint = "xxxxxxxx-xxxxxxxx-xxxxxxxx",
            visualTransformation = PasswordVisualTransformation(),
            onValueChange = { v -> viewModel.update { it.copy(license = v) } },
            modifier = boxModifier,
        )
        if (WarpVariant.AWG in form.output.variants) {
            ConfigurationTextBox(
                value = form.mimicHost,
                label = stringResource(R.string.quartz_warp_mimic_host),
                hint = stringResource(R.string.quartz_warp_mimic_host_hint),
                onValueChange = { v -> viewModel.update { it.copy(mimicHost = v) } },
                modifier = boxModifier,
            )
        }
        if (groups.isNotEmpty()) {
            DescriptionText(
                stringResource(R.string.quartz_warp_group),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val none = -1 to stringResource(R.string.quartz_warp_no_group)
            Chips(
                listOf(none) + groups,
                groups.firstOrNull { it.first == form.groupId } ?: none,
                label = { it.second },
            ) { picked ->
                viewModel.update { it.copy(groupId = picked.first.takeIf { id -> id >= 0 }) }
            }
            DescriptionText(
                stringResource(R.string.quartz_warp_group_desc),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        SurfaceRow(
            leading = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = stringResource(R.string.quartz_warp_delete_old),
            description = { DescriptionText(stringResource(R.string.quartz_warp_delete_old_desc)) },
            trailing = { modifier ->
                ThemedSwitch(
                    checked = form.deleteOldRegistration,
                    onClick = { v -> viewModel.update { it.copy(deleteOldRegistration = v) } },
                    modifier = modifier,
                )
            },
            onClick = {
                viewModel.update { it.copy(deleteOldRegistration = !it.deleteOldRegistration) }
            },
        )
        SurfaceRow(
            leading = { Icon(Icons.Outlined.LinkOff, null) },
            title = stringResource(R.string.quartz_warp_revoke),
            description = { DescriptionText(stringResource(R.string.quartz_warp_revoke_desc)) },
            trailing = { modifier ->
                ThemedSwitch(
                    checked = form.revokeOnExpiry,
                    onClick = { v -> viewModel.update { it.copy(revokeOnExpiry = v) } },
                    modifier = modifier,
                )
            },
            onClick = { viewModel.update { it.copy(revokeOnExpiry = !it.revokeOnExpiry) } },
        )
    }
}

@Composable
private fun GeneratedCard(
    generated: GeneratedWarp,
    replaces: Int,
    busy: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onExport: (WarpVariant) -> Unit,
    onQr: (WarpVariant) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                stringResource(R.string.quartz_warp_generated),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                listOfNotNull(
                        generated.registration.accountType?.let {
                            stringResource(R.string.quartz_warp_account, it)
                        },
                        stringResource(R.string.quartz_warp_endpoint_value, generated.endpoint),
                        generated.expiresAt?.let {
                            stringResource(R.string.quartz_warp_expires_on, formatDate(it))
                        } ?: stringResource(R.string.quartz_warp_never_expires),
                    )
                    .joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
            generated.configs.keys.forEach { variant ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        WarpConfigBuilder.tunnelName(generated.options.slot, variant),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onExport(variant) }) {
                        Text(stringResource(R.string.quartz_warp_export))
                    }
                    TextButton(onClick = { onQr(variant) }) {
                        Text(stringResource(R.string.quartz_warp_qr))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !busy) {
                    Text(
                        if (replaces > 0) stringResource(R.string.quartz_warp_replace, replaces)
                        else stringResource(R.string.quartz_warp_import)
                    )
                }
                OutlinedButton(onClick = onDiscard, enabled = !busy) {
                    Text(stringResource(R.string.quartz_warp_discard))
                }
            }
        }
    }
}

@Composable
private fun SlotTunnelRow(slotTunnel: SlotTunnel, onDetach: () -> Unit) {
    val meta = slotTunnel.meta
    val details =
        listOfNotNull(
                slotTunnel.variant?.let {
                    stringResource(
                        if (it == WarpVariant.AWG) R.string.quartz_warp_output_awg
                        else R.string.quartz_warp_output_wg
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        GroupLabel(title, modifier = Modifier.padding(horizontal = 16.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
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

@Composable
private fun NumberBox(
    label: String,
    value: String,
    isError: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    ConfigurationTextBox(
        value = value,
        label = label,
        hint = "",
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        onValueChange = onChange,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryDatePicker(initial: Long?, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // The picker returns UTC midnight of the chosen day; expire at the end of that
                    // day, local time.
                    pickerState.selectedDateMillis?.let { utc ->
                        val day = Instant.ofEpochMilli(utc).atZone(ZoneId.of("UTC")).toLocalDate()
                        onPicked(
                            day.plusDays(1)
                                .atStartOfDay(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli() - 1
                        )
                    } ?: onDismiss()
                }
            ) {
                Text(stringResource(R.string.okay))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun formatDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun WarpOutput.labelRes() =
    when (this) {
        WarpOutput.WIREGUARD -> R.string.quartz_warp_output_wg
        WarpOutput.AMNEZIA -> R.string.quartz_warp_output_awg
        WarpOutput.BOTH -> R.string.quartz_warp_output_both
    }

private fun JunkPreset.labelRes() =
    when (this) {
        JunkPreset.LIGHT -> R.string.quartz_warp_junk_light
        JunkPreset.MEDIUM -> R.string.quartz_warp_junk_medium
        JunkPreset.HEAVY -> R.string.quartz_warp_junk_heavy
        JunkPreset.CUSTOM -> R.string.quartz_warp_custom
    }

private fun ExpiryChoice.labelRes() =
    when (this) {
        ExpiryChoice.DAY_1 -> R.string.quartz_warp_expiry_1d
        ExpiryChoice.DAYS_7 -> R.string.quartz_warp_expiry_7d
        ExpiryChoice.DAYS_30 -> R.string.quartz_warp_expiry_30d
        ExpiryChoice.DAYS_90 -> R.string.quartz_warp_expiry_90d
        ExpiryChoice.CUSTOM -> R.string.quartz_warp_custom_date
        ExpiryChoice.NEVER -> R.string.quartz_warp_never
    }

private fun EndpointMode.labelRes() =
    when (this) {
        EndpointMode.DEFAULT -> R.string.quartz_warp_endpoint_default
        EndpointMode.RANDOM -> R.string.quartz_warp_endpoint_random
        EndpointMode.CUSTOM -> R.string.quartz_warp_custom
    }
