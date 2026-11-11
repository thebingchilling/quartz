package app.quartz.bridge

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed interface OpenVpnState {
    data object Idle : OpenVpnState

    data class Starting(val tunnelId: Int) : OpenVpnState

    /** Connected; [reconnecting] while openvpn3 re-establishes the session (network change). */
    data class Running(val tunnelId: Int, val startedAt: Long, val reconnecting: Boolean = false) :
        OpenVpnState

    data class Failed(val tunnelId: Int, val message: String) : OpenVpnState
}

/**
 * Starts and stops an OpenVPN profile through [OpenVpnVpnService] (which owns the TUN and the
 * openvpn3 session) and [EngineCompanionService] (the foreground notification), in the same shape
 * as [MihomoRunner].
 */
class OpenVpnRunner(private val context: Context) {
    private val _state = MutableStateFlow<OpenVpnState>(OpenVpnState.Idle)
    val state: StateFlow<OpenVpnState> = _state.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var request: Request? = null
    @Volatile private var result: CompletableDeferred<Result<Unit>>? = null

    internal data class Request(
        val tunnelId: Int,
        val name: String,
        val profile: String,
        val credentials: OpenVpnCredentials?,
    )

    suspend fun start(
        tunnelId: Int,
        name: String,
        profile: String,
        credentials: OpenVpnCredentials?,
    ): Result<Unit> =
        mutex.withLock {
            val pending = CompletableDeferred<Result<Unit>>()
            request = Request(tunnelId, name, profile, credentials)
            result = pending
            _state.value = OpenVpnState.Starting(tunnelId)
            runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, EngineCompanionService::class.java)
                            .setAction(EngineCompanionService.ACTION_START_VPN)
                            .putExtra(
                                EngineCompanionService.EXTRA_ENGINE,
                                EngineCompanionService.ENGINE_OPENVPN,
                            ),
                    )
                }
                .onFailure { onServiceStartFailed(it) }
            runCatching { withTimeout(START_TIMEOUT) { pending.await() } }
                .getOrElse { Result.failure(it) }
                .onFailure {
                    if (_state.value !is OpenVpnState.Running) {
                        _state.value =
                            OpenVpnState.Failed(tunnelId, it.message ?: "Couldn't connect")
                    }
                    // A session still trying to connect after the timeout mustn't keep going.
                    sendStop()
                }
        }

    suspend fun stop() = mutex.withLock {
        if (_state.value is OpenVpnState.Idle) return@withLock
        if (sendStop()) runCatching {
            withTimeout(STOP_TIMEOUT) {
                _state.first { it is OpenVpnState.Idle || it is OpenVpnState.Failed }
            }
        }
        _state.value = OpenVpnState.Idle
    }

    private fun sendStop(): Boolean =
        runCatching {
                context.startService(
                    Intent(context, OpenVpnVpnService::class.java)
                        .setAction(OpenVpnVpnService.ACTION_STOP)
                )
            }
            .isSuccess

    // Called by OpenVpnVpnService.

    internal fun takeRequest(): Request? = request.also { request = null }

    internal fun onConnected(tunnelId: Int) {
        val current = _state.value
        _state.value =
            if (current is OpenVpnState.Running && current.tunnelId == tunnelId)
                current.copy(reconnecting = false)
            else OpenVpnState.Running(tunnelId, System.currentTimeMillis())
        result?.complete(Result.success(Unit))
    }

    internal fun onReconnecting(tunnelId: Int) {
        val current = _state.value
        if (current is OpenVpnState.Running && current.tunnelId == tunnelId)
            _state.value = current.copy(reconnecting = true)
    }

    internal fun onFailed(tunnelId: Int, message: String) {
        _state.value = OpenVpnState.Failed(tunnelId, message)
        result?.complete(Result.failure(IllegalStateException(message)))
    }

    internal fun onServiceStartFailed(error: Throwable) {
        val tunnelId = (_state.value as? OpenVpnState.Starting)?.tunnelId ?: return
        onFailed(tunnelId, error.message ?: "Couldn't start the VPN service")
    }

    /**
     * The session ended (asked to, or on its own). OpenVpnVpnService only reports its current
     * session, so a stopped start ends here too.
     */
    internal fun onStopped() {
        when (_state.value) {
            is OpenVpnState.Running -> _state.value = OpenVpnState.Idle
            is OpenVpnState.Starting -> {
                _state.value = OpenVpnState.Idle
                result?.complete(Result.failure(IllegalStateException("Stopped")))
            }
            else -> Unit
        }
    }

    private companion object {
        // openvpn3 keeps trying every remote in the profile; give up on the start after this.
        val START_TIMEOUT = 60.seconds
        val STOP_TIMEOUT = 10.seconds
    }
}
