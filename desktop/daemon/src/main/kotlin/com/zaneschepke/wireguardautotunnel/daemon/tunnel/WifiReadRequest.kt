package com.zaneschepke.wireguardautotunnel.daemon.tunnel

import com.wgtunnel.backend.network.NetworkInfoDto
import com.zaneschepke.wireguardautotunnel.core.wifi.isUnknownWifiSsid

/**
 * Whether a new underlay snapshot should ask the Wi-Fi helper to read.
 *
 * A null [previous] is the monitor's first snapshot and is not an ask.
 * The helper's own report is what turns a blank or `unknown_ssid` into a real name, so that change
 * is not an ask. A name disappearing, changing from one known name to another,
 * or a different interface still asks.
 */
internal fun shouldAskWifiRead(previous: NetworkInfoDto?, current: NetworkInfoDto): Boolean {
    if (previous == null) return false
    if (previous.type != current.type) return true
    if (previous.interfaceName != current.interfaceName) return true
    if (previous.ifIndex != current.ifIndex) return true
    if (previous.ssid == current.ssid) return false
    if (isUnknownWifiSsid(previous.ssid) && !isUnknownWifiSsid(current.ssid)) return false
    return true
}
