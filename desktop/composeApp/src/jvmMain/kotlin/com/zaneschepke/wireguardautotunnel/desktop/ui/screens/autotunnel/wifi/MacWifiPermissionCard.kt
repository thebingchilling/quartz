package com.zaneschepke.wireguardautotunnel.desktop.ui.screens.autotunnel.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zaneschepke.wireguardautotunnel.composeapp.generated.resources.Res
import com.zaneschepke.wireguardautotunnel.composeapp.generated.resources.wifi_name_allow
import com.zaneschepke.wireguardautotunnel.composeapp.generated.resources.wifi_name_permission_body
import com.zaneschepke.wireguardautotunnel.composeapp.generated.resources.wifi_name_permission_title
import com.zaneschepke.wireguardautotunnel.desktop.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.desktop.wifi.MacWifiNameCoordinator
import com.zaneschepke.wireguardautotunnel.desktop.wifi.needsLocationPrompt
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun MacWifiPermissionNotice(coordinator: MacWifiNameCoordinator = koinInject()) {
    val status by coordinator.authorization.collectAsState()
    if (!status.needsLocationPrompt) return
    MacWifiPermissionCard(onOpenSettings = coordinator::openLocationSettings)
}

@Composable
fun MacWifiPermissionCard(onOpenSettings: () -> Unit) {
    val title = stringResource(Res.string.wifi_name_permission_title)
    val body = stringResource(Res.string.wifi_name_permission_body)
    val button = stringResource(Res.string.wifi_name_allow)

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        DescriptionText(body)
        Button(onClick = onOpenSettings) { Text(button) }
    }
}
