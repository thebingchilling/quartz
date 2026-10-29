package com.zaneschepke.wireguardautotunnel.core.ipc.dto.request

import kotlinx.serialization.Serializable

// Wi-Fi name read by the macOS helper.
// A null ssid leaves the current name alone. An empty ssid clears it.
// authorization is the helper's Location status code.
@Serializable
data class WifiSsidReport(val ssid: String? = null, val authorization: Int)
