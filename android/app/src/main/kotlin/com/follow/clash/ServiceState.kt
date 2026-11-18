package com.follow.clash

import com.follow.clash.autotunnel.AutoTunnelState
import com.follow.clash.models.SharedState
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Deferred

object ServiceState {
    private val machine = ServiceStateMachine(AndroidServiceStateHost)

    val runState = machine.runState

    val isRunRequested: Boolean
        get() = machine.isRunRequested

    fun attachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.attachFlutterEngine(engine)

    fun detachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.detachFlutterEngine(engine)

    suspend fun handleToggleAction() {
        if (isRunRequested) AutoTunnelState.onUserStop()
        machine.handleToggleAction()
    }

    suspend fun handleStartAction() = machine.handleStartAction()

    suspend fun handleStopAction() {
        AutoTunnelState.onUserStop()
        machine.handleStopAction()
    }

    /** A stop auto-tunnel makes, which isn't the user's. */
    suspend fun handleSwitchStop() = machine.handleStopAction()

    suspend fun handleVpnRevokeAction() = machine.handleVpnRevokeAction()

    suspend fun awaitRunTime(): Long = machine.awaitRunTime()

    fun requestStart(): Deferred<Boolean> = machine.requestStart()

    fun requestStop(): Deferred<Boolean> {
        AutoTunnelState.onUserStop()
        return machine.requestStop()
    }

    fun syncSharedState(state: SharedState) = machine.syncSharedState(state)

    internal fun captureRequestToken(): RunRequest = machine.captureRequestToken()

    internal suspend fun handleServiceLost(token: RunRequest) = machine.handleServiceLost(token)
}
