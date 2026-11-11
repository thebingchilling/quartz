package app.quartz.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quartz.bridge.EnginePlaceholder
import app.quartz.bridge.OpenVpnCredentials
import app.quartz.bridge.OpenVpnProfiles
import app.quartz.bridge.Subscriptions
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The top of upstream's tunnel settings screen for a Clash or OpenVPN tunnel (one line in
 * TunnelSettingsScreen.kt), in place of upstream's WireGuard "Configuration" section, which would
 * only show the placeholder config. Clash: its subscription (update now, provider page). OpenVPN:
 * the username and password a profile asks for. Returns false for WireGuard tunnels, which keep
 * upstream's section.
 */
@Composable
fun QuartzEngineSettings(tunnelId: Int, quickConfig: String): Boolean {
    return when (EnginePlaceholder.engineOf(quickConfig)) {
        EngineProfile.ENGINE_MIHOMO -> {
            ClashSubscriptionSettings(tunnelId)
            true
        }
        EngineProfile.ENGINE_OPENVPN -> {
            OpenVpnLoginSettings(tunnelId)
            true
        }
        else -> false
    }
}

@Composable
private fun ClashSubscriptionSettings(
    tunnelId: Int,
    profileDao: EngineProfileDao = koinInject(),
    subscriptions: Subscriptions = koinInject(),
    tunnelCoordinator: TunnelCoordinator = koinInject(),
) {
    val profileFlow =
        remember(tunnelId) {
            profileDao.flow().map { list -> list.firstOrNull { it.tunnelId == tunnelId } }
        }
    val profile by profileFlow.collectAsStateWithLifecycle(null)
    val sub = profile?.takeIf { it.subscriptionUrl != null } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column {
        GroupLabel(
            stringResource(R.string.quartz_clash_subscription),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SurfaceRow(
            leading = { Icon(Icons.Outlined.Sync, contentDescription = null) },
            title = stringResource(R.string.quartz_clash_update_now),
            description = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    QuartzSubscriptionSummary(sub)
                    (error ?: sub.lastError)?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            trailing =
                if (updating) {
                    { _ -> CircularProgressIndicator(Modifier.size(20.dp)) }
                } else null,
            enabled = !updating,
            onClick = {
                updating = true
                error = null
                scope.launch {
                    subscriptions
                        .update(tunnelId)
                        .onSuccess { tunnelCoordinator.restartActiveTunnels() }
                        .onFailure { error = it.message ?: "Update failed" }
                    updating = false
                }
            },
        )
        sub.webPageUrl?.let { url ->
            SurfaceRow(
                leading = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                title = stringResource(R.string.quartz_clash_web_page),
                description = { DescriptionText(url, maxLines = 1) },
                onClick = { context.openWebUrl(url) },
            )
        }
    }
}

@Composable
private fun OpenVpnLoginSettings(tunnelId: Int, profiles: OpenVpnProfiles = koinInject()) {
    var loaded by remember(tunnelId) { mutableStateOf(false) }
    var username by remember(tunnelId) { mutableStateOf("") }
    var password by remember(tunnelId) { mutableStateOf("") }
    LaunchedEffect(tunnelId) {
        profiles.credentials(tunnelId)?.let {
            username = it.username
            password = it.password
        }
        loaded = true
    }
    // Saved as typed (debounced); the next connect uses them.
    LaunchedEffect(username, password, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(400)
        profiles.saveCredentials(
            tunnelId,
            username.trim().takeIf { it.isNotEmpty() }?.let { OpenVpnCredentials(it, password) },
        )
    }

    Column {
        GroupLabel(
            stringResource(R.string.quartz_openvpn_login),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DescriptionText(stringResource(R.string.quartz_openvpn_login_desc))
            ConfigurationTextBox(
                value = username,
                label = stringResource(R.string.quartz_openvpn_username),
                hint = "",
                onValueChange = { username = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            ConfigurationTextBox(
                value = password,
                label = stringResource(R.string.quartz_openvpn_password),
                hint = "",
                onValueChange = { password = it },
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}
