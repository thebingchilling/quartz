package com.zaneschepke.wireguardautotunnel.core.wifi

// Matches the daemon's `unknown_ssid`
const val UNKNOWN_WIFI_SSID = "unknown_ssid"

fun isUnknownWifiSsid(ssid: String): Boolean {
    val name = ssid.trim()
    return name.isEmpty() || name == UNKNOWN_WIFI_SSID
}

fun wifiSsidToReport(granted: Boolean, associated: Boolean, ssid: String): String? {
    if (!granted) return null
    val name = ssid.trim()
    if (associated && name.isEmpty()) return null
    return name
}

sealed interface WifiReadStep {
    data class Post(val ssid: String) : WifiReadStep
    data object Wait : WifiReadStep
    data object Stop : WifiReadStep
    data object RecheckAuth : WifiReadStep
}

fun wifiReadStep(
    granted: Boolean,
    associated: Boolean,
    ssid: String,
    deadlineElapsed: Boolean,
): WifiReadStep {
    if (!granted) return WifiReadStep.RecheckAuth
    if (!associated) return WifiReadStep.Post("")
    val report =
        wifiSsidToReport(granted = true, associated = true, ssid = ssid)
            ?: return if (deadlineElapsed) WifiReadStep.Stop else WifiReadStep.Wait
    return WifiReadStep.Post(report)
}
