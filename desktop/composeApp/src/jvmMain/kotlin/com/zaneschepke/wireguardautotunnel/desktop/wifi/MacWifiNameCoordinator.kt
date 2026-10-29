package com.zaneschepke.wireguardautotunnel.desktop.wifi

import co.touchlab.kermit.Logger
import com.zaneschepke.wireguardautotunnel.client.service.DaemonService
import com.zaneschepke.wireguardautotunnel.core.wifi.WifiAuthorization
import dev.nucleusframework.core.runtime.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks the macOS Wi-Fi helper's Location grant.
 *
 * The helper is a separate bundle, so a grant on the GUI process does not let it read the
 * network name. The card follows the code the helper posts to the daemon.
 */
class MacWifiNameCoordinator(
    private val scope: CoroutineScope,
    private val daemonService: DaemonService,
) {
    private val log = Logger.withTag("MacWifiName")
    private val _authorization = MutableStateFlow<WifiAuthorization?>(null)
    val authorization: StateFlow<WifiAuthorization?> = _authorization.asStateFlow()

    init {
        if (Platform.Current == Platform.MacOS) {
            scope.launch {
                daemonService.autoTunnelStatusFlow().collect { status ->
                    _authorization.value =
                        status.wifiAuthorization?.let(WifiAuthorization::fromCode)
                }
            }
        }
    }

    fun openLocationSettings() {
        if (Platform.Current != Platform.MacOS) return
        scope.launch {
            for (url in LOCATION_SETTINGS_URLS) {
                val opened = runCatching {
                    ProcessBuilder("open", url).start().waitFor() == 0
                }
                    .getOrDefault(false)
                if (opened) return@launch
            }
            log.w { "Could not open Location Services settings" }
        }
    }

    private companion object {
        val LOCATION_SETTINGS_URLS =
            listOf(
                "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_LocationServices",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices",
            )
    }
}

/**
 * True when the macOS Wi-Fi helper needs the Location prompt shown, whether that's because
 * permission was denied or Location Services itself is off - both read the same way to a user, so
 * [MacWifiPermissionCard] doesn't distinguish them. Null means the helper hasn't reported a status
 * yet, so the prompt stays hidden rather than flashing it before the real state is known.
 */
val WifiAuthorization?.needsLocationPrompt: Boolean
    get() = Platform.Current == Platform.MacOS && this != null && !granted
