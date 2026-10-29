package com.zaneschepke.wireguardautotunnel.client.domain.model

import com.wgtunnel.backend.model.KillSwitchConfig
import com.zaneschepke.wireguardautotunnel.core.ipc.dto.KillSwitchConfigDto
import kotlinx.serialization.Serializable

@Serializable
data class LockdownSettings(
    val id: Long = 1L,
    val enabled: Boolean = false,
    val restoreOnBoot: Boolean = false,
    val bypassLan: Boolean = false,
) {
    fun toKillSwitchConfig(dnsServers: Collection<String> = emptyList()): KillSwitchConfig {
        return KillSwitchConfig(
            allowedIps = if (bypassLan) LOCAL_NETWORKS else emptySet(),
            metered = false,
            dualStack = true,
        )
    }

    companion object {
        // Unicast private ranges plus the local broadcast and multicast groups LAN
        // discovery and DHCP use.
        private val LOCAL_NETWORKS =
            setOf(
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "169.254.0.0/16",
                "255.255.255.255/32",
                "224.0.0.0/24",
                "239.0.0.0/8",
                "fc00::/7",
                "fe80::/10",
                "ff01::/16",
                "ff02::/16",
                "ff03::/16",
                "ff04::/16",
                "ff05::/16",
            )
    }

    fun toDto(dnsServers: Collection<String> = emptyList()): KillSwitchConfigDto {
        val core = toKillSwitchConfig(dnsServers)
        return KillSwitchConfigDto(allowedIps = core.allowedIps, dualStack = true)
    }
}
