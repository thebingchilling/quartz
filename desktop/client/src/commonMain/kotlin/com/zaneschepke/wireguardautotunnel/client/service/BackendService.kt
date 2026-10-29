package com.zaneschepke.wireguardautotunnel.client.service

import com.zaneschepke.wireguardautotunnel.core.ipc.dto.BackendStatus
import com.zaneschepke.wireguardautotunnel.core.ipc.dto.KillSwitchConfigDto
import com.zaneschepke.wireguardautotunnel.core.ipc.dto.request.BootstrapDnsModeRequest
import kotlinx.coroutines.flow.Flow

interface BackendService {
    suspend fun setKillSwitch(enabled: Boolean, config: KillSwitchConfigDto? = null): Result<Unit>

    suspend fun setBootstrapDnsMode(mode: BootstrapDnsModeRequest): Result<Unit>

    suspend fun getStatus(): Result<BackendStatus>

    fun statusFlow(): Flow<BackendStatus>
}
