package com.zaneschepke.wireguardautotunnel.daemon.data

import com.zaneschepke.wireguardautotunnel.core.ipc.dto.KillSwitchConfigDto
import com.zaneschepke.wireguardautotunnel.core.ipc.dto.request.BootstrapDnsModeRequest

interface DaemonCacheRepository {
    suspend fun updateKillSwitchEnabled(enabled: Boolean)

    suspend fun updateKillSwitchConfig(config: KillSwitchConfigDto?)

    suspend fun updateKillSwitchRestore(enabled: Boolean)

    suspend fun getKillSwitchEnabled(): Boolean

    suspend fun getKillSwitchConfig(): KillSwitchConfigDto?

    suspend fun getKillSwitchRestore(): Boolean

    suspend fun setLocalLoggingEnabled(enabled: Boolean)

    suspend fun getLocalLoggingEnabled(): Boolean

    suspend fun updateBootstrapDnsMode(mode: BootstrapDnsModeRequest?)

    suspend fun getBootstrapDnsMode(): BootstrapDnsModeRequest?
}
