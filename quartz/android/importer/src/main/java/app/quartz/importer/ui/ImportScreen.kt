package app.quartz.importer.ui

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
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.importer.Detected
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberFileImportLauncherForResult
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.util.StringValue
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun ImportScreen(
    viewModel: ImportViewModel = koinViewModel(),
    sharedViewModel: SharedAppViewModel = koinActivityViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val clipboard = rememberClipboardHelper()
    val pickFile =
        rememberFileImportLauncherForResult(
            onNoFileExplorer = {
                sharedViewModel.showSnackMessage(
                    StringValue.StringResource(R.string.error_no_file_explorer),
                    ToastType.Error,
                )
            },
            onData = viewModel::detectFile,
        )

    LaunchedEffect(state.savedTunnelId) {
        val id = state.savedTunnelId ?: return@LaunchedEffect
        navController.pop()
        if (id > 0) navController.push(Route.Clash(id))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        Column {
            GroupLabel(
                stringResource(R.string.quartz_import_source),
                Modifier.padding(horizontal = 16.dp),
            )
            DescriptionText(
                stringResource(R.string.quartz_import_source_desc),
                Modifier.padding(horizontal = 16.dp),
            )
            ConfigurationTextBox(
                value = state.input,
                label = stringResource(R.string.quartz_import_input),
                hint = "https://… · vless://… · proxies:",
                singleLine = false,
                onValueChange = { v -> viewModel.update { it.copy(input = v) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        clipboard.paste { text -> if (text != null) viewModel.detect(text) }
                    }
                ) {
                    Icon(Icons.Outlined.ContentPasteGo, null, Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quartz_import_paste))
                }
                OutlinedButton(onClick = pickFile) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quartz_import_file))
                }
                Button(
                    onClick = { viewModel.detect() },
                    enabled = state.input.isNotBlank() && !state.busy,
                ) {
                    Icon(Icons.Outlined.Search, null, Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quartz_import_detect))
                }
            }
        }

        if (state.busy) {
            CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(28.dp))
        }
        state.error?.let { ErrorText(it) }

        state.detected?.let { detected ->
            Preview(
                state = state,
                detected = detected,
                groups = groups.map { it.id to it.name },
                viewModel = viewModel,
                onImportWireGuard = { text ->
                    sharedViewModel.importFromClipboard(text)
                    viewModel.markWireGuardImported()
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Preview(
    state: ImportUiState,
    detected: Detected,
    groups: List<Pair<Int, String>>,
    viewModel: ImportViewModel,
    onImportWireGuard: (String) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                stringResource(R.string.quartz_import_detected),
                style = MaterialTheme.typography.titleMedium,
            )
            when (detected) {
                is Detected.WireGuard -> {
                    Text(stringResource(R.string.quartz_import_type_wireguard))
                    Button(onClick = { onImportWireGuard(detected.config) }) {
                        Text(stringResource(R.string.quartz_import_as_wireguard))
                    }
                }
                is Detected.OpenVpn -> Text(stringResource(R.string.quartz_import_type_openvpn))
                is Detected.Unknown -> ErrorText(detected.reason)
                is Detected.SubscriptionUrl -> Text(detected.url)
                is Detected.ClashProfile -> {
                    Text(stringResource(R.string.quartz_import_type_profile, detected.proxyCount))
                    ClashOptions(state, groups, viewModel, showTemplate = false)
                }
                is Detected.ProxyList -> {
                    Text(stringResource(R.string.quartz_import_type_proxies, detected.proxies.size))
                    if (detected.providers.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.quartz_import_providers,
                                detected.providers.size,
                            )
                        )
                    }
                    if (detected.skipped.isNotEmpty()) {
                        DescriptionText(
                            stringResource(
                                R.string.quartz_import_skipped,
                                detected.skipped.size,
                                detected.skipped.take(5).joinToString(", "),
                            )
                        )
                    }
                    if (detected.isSingleWireGuard) {
                        OutlinedButton(
                            onClick = {
                                onImportWireGuard(
                                    ImportViewModel.wireGuardConfig(detected.proxies[0])
                                )
                            }
                        ) {
                            Text(stringResource(R.string.quartz_import_as_wireguard))
                        }
                    }
                    ClashOptions(state, groups, viewModel, showTemplate = true)
                }
            }
        }
    }
}

@Composable
private fun ClashOptions(
    state: ImportUiState,
    groups: List<Pair<Int, String>>,
    viewModel: ImportViewModel,
    showTemplate: Boolean,
) {
    state.info?.let { info ->
        val parts =
            listOfNotNull(
                info.totalBytes?.let { total ->
                    stringResource(
                        R.string.quartz_import_usage,
                        gib(info.usedBytes ?: 0),
                        gib(total),
                    )
                },
                info.expireAt?.let {
                    stringResource(R.string.quartz_import_sub_expires, formatDate(it * 1000))
                },
            )
        if (parts.isNotEmpty()) DescriptionText(parts.joinToString(" · "))
    }
    ConfigurationTextBox(
        value = state.name,
        label = stringResource(R.string.quartz_import_name),
        hint = "Clash",
        onValueChange = { v -> viewModel.update { it.copy(name = v) } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (showTemplate) {
        DescriptionText(stringResource(R.string.quartz_import_template))
        Chips(TemplateChoice.entries, state.template, { stringResource(it.label()) }) { picked ->
            viewModel.update { it.copy(template = picked) }
        }
        when (state.template) {
            TemplateChoice.RULE_BASED ->
                ConfigurationTextBox(
                    value = state.country,
                    label = stringResource(R.string.quartz_import_country),
                    hint = "NZ",
                    onValueChange = { v ->
                        viewModel.update { it.copy(country = v.take(2).uppercase()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            TemplateChoice.CUSTOM ->
                ConfigurationTextBox(
                    value = state.customTemplate,
                    label = stringResource(R.string.quartz_import_custom),
                    hint = "proxies:{{proxies}}",
                    singleLine = false,
                    onValueChange = { v -> viewModel.update { it.copy(customTemplate = v) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            TemplateChoice.SIMPLE -> Unit
        }
    }
    if (state.subscriptionUrl != null) {
        ConfigurationTextBox(
            value = state.updateIntervalHours,
            label = stringResource(R.string.quartz_import_interval),
            hint = "24",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onValueChange = { v ->
                viewModel.update { it.copy(updateIntervalHours = v.filter(Char::isDigit)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SurfaceRow(
            title = stringResource(R.string.quartz_import_wifi_only),
            trailing = { modifier ->
                ThemedSwitch(
                    checked = state.wifiOnly,
                    onClick = { v -> viewModel.update { it.copy(wifiOnly = v) } },
                    modifier = modifier,
                )
            },
            onClick = { viewModel.update { it.copy(wifiOnly = !it.wifiOnly) } },
        )
        ConfigurationTextBox(
            value = state.userAgent,
            label = stringResource(R.string.quartz_import_user_agent),
            hint = "clash.meta",
            onValueChange = { v -> viewModel.update { it.copy(userAgent = v) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (groups.isNotEmpty()) {
        DescriptionText(stringResource(R.string.quartz_import_group))
        val none = -1 to stringResource(R.string.quartz_import_no_group)
        Chips(
            listOf(none) + groups,
            groups.firstOrNull { it.first == state.groupId } ?: none,
            { it.second },
        ) { picked ->
            viewModel.update { it.copy(groupId = picked.first.takeIf { id -> id >= 0 }) }
        }
    }
    state.validationWarning?.let {
        ErrorText(stringResource(R.string.quartz_import_validation, it))
        Button(onClick = { viewModel.save(force = true) }, enabled = !state.busy) {
            Text(stringResource(R.string.quartz_import_save_anyway))
        }
    }
        ?: Button(onClick = { viewModel.save() }, enabled = !state.busy) {
            Text(stringResource(R.string.quartz_import_save))
        }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
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
        verticalArrangement = Arrangement.Center,
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

private fun TemplateChoice.label() =
    when (this) {
        TemplateChoice.SIMPLE -> R.string.quartz_import_template_simple
        TemplateChoice.RULE_BASED -> R.string.quartz_import_template_rules
        TemplateChoice.CUSTOM -> R.string.quartz_import_template_custom
    }

internal fun gib(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)

internal fun formatDate(millis: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
