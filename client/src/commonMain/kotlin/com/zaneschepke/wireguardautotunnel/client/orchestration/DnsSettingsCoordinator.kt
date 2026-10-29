package com.zaneschepke.wireguardautotunnel.client.orchestration

import co.touchlab.kermit.Logger
import com.zaneschepke.wireguardautotunnel.client.domain.repository.DnsSettingsRepository
import com.zaneschepke.wireguardautotunnel.client.service.BackendService
import com.zaneschepke.wireguardautotunnel.client.service.DaemonService
import com.zaneschepke.wireguardautotunnel.client.util.toBootstrapDnsModeRequest
import com.zaneschepke.wireguardautotunnel.core.ipc.dto.request.BootstrapDnsModeRequest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pushes the persisted bootstrap DNS mode whenever it changes, and again on every daemon reconnect.
 * Tunnel starts also send the mode and the daemon applies it before bootstrap.
 */
class DnsSettingsCoordinator(
    private val backendService: BackendService,
    private val daemonService: DaemonService,
    dnsSettingsRepository: DnsSettingsRepository,
    scope: CoroutineScope,
) {
    private val log = Logger.withTag("DnsSettingsCoordinator")
    private val modeToPush = MutableStateFlow<BootstrapDnsModeRequest?>(null)
    private var lastPushed: BootstrapDnsModeRequest? = null

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            dnsSettingsRepository.flow
                .map { it.toBootstrapDnsModeRequest() }
                .distinctUntilChanged()
                .debounce(300.milliseconds)
                .collectLatest { modeToPush.value = it }
        }
        scope.launch {
            combine(modeToPush.filterNotNull(), daemonService.alive) { mode, alive ->
                    mode to alive
                }
                .collectLatest { (mode, alive) ->
                    if (!alive) {
                        lastPushed = null
                        return@collectLatest
                    }
                    if (mode == lastPushed) return@collectLatest
                    if (pushMode(mode)) lastPushed = mode
                }
        }
    }

    private suspend fun pushMode(mode: BootstrapDnsModeRequest): Boolean {
        while (currentCoroutineContext().isActive) {
            val result = backendService.setBootstrapDnsMode(mode)
            if (result.isSuccess) {
                log.d { "Applied bootstrap DNS mode protocol=${mode.protocol}" }
                return true
            }
            if (!daemonService.alive()) {
                log.w(result.exceptionOrNull()) {
                    "Failed to apply bootstrap DNS mode, daemon is not healthy"
                }
                return false
            }
            log.w(result.exceptionOrNull()) { "Failed to push bootstrap DNS mode, retrying..." }
            delay(2.seconds)
        }
        return false
    }
}
