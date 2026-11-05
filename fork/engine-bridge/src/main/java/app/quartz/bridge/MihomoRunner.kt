package app.quartz.bridge

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.quartz.engine.mihomo.MihomoController
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed interface MihomoState {
    data object Idle : MihomoState

    data class Starting(val tunnelId: Int) : MihomoState

    data class Running(val tunnelId: Int, val controller: MihomoController, val startedAt: Long) :
        MihomoState

    data class Failed(val tunnelId: Int, val message: String) : MihomoState
}

/**
 * Starts and stops a Clash profile through [MihomoVpnService] (which owns the TUN) and
 * [MihomoCompanionService] (the foreground notification), and reports what's running.
 */
class MihomoRunner(private val context: Context) {
    private val _state = MutableStateFlow<MihomoState>(MihomoState.Idle)
    val state: StateFlow<MihomoState> = _state.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var request: Request? = null
    @Volatile private var result: CompletableDeferred<Result<MihomoController>>? = null

    internal data class Request(val tunnelId: Int, val name: String, val profile: File)

    suspend fun start(tunnelId: Int, name: String, profile: File): Result<MihomoController> =
        mutex.withLock {
            val pending = CompletableDeferred<Result<MihomoController>>()
            request = Request(tunnelId, name, profile)
            result = pending
            _state.value = MihomoState.Starting(tunnelId)
            ContextCompat.startForegroundService(
                context,
                Intent(context, MihomoCompanionService::class.java),
            )
            context.startService(
                Intent(context, MihomoVpnService::class.java)
                    .setAction(MihomoVpnService.ACTION_START)
            )
            runCatching { withTimeout(START_TIMEOUT) { pending.await() } }
                .getOrElse { Result.failure(it) }
                .onFailure { stopServices() }
        }

    suspend fun stop() = mutex.withLock {
        if (_state.value is MihomoState.Idle) return@withLock
        context.startService(
            Intent(context, MihomoVpnService::class.java).setAction(MihomoVpnService.ACTION_STOP)
        )
        runCatching {
            withTimeout(STOP_TIMEOUT) {
                _state.first { it is MihomoState.Idle || it is MihomoState.Failed }
            }
        }
        stopServices()
        _state.value = MihomoState.Idle
    }

    private fun stopServices() {
        context.stopService(Intent(context, MihomoCompanionService::class.java))
    }

    // Called by MihomoVpnService.

    internal fun takeRequest(): Request? = request.also { request = null }

    internal fun onStarted(tunnelId: Int, controller: MihomoController) {
        _state.value = MihomoState.Running(tunnelId, controller, System.currentTimeMillis())
        result?.complete(Result.success(controller))
    }

    internal fun onFailed(tunnelId: Int, message: String) {
        _state.value = MihomoState.Failed(tunnelId, message)
        result?.complete(Result.failure(IllegalStateException(message)))
    }

    internal fun onStopped() {
        if (_state.value !is MihomoState.Failed) _state.value = MihomoState.Idle
        stopServices()
    }

    private companion object {
        val START_TIMEOUT = 45.seconds
        val STOP_TIMEOUT = 10.seconds
    }
}
