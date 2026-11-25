package com.follow.clash

import android.content.Context
import com.follow.clash.autotunnel.AutoTunnelState
import com.follow.clash.autotunnel.KillSwitch
import com.follow.clash.common.GlobalState
import com.follow.clash.models.SharedState
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Deferred

object ServiceState {
    private val machine = ServiceStateMachine(AndroidServiceStateHost)

    private val prefs by lazy {
        GlobalState.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val runState = machine.runState

    val isRunRequested: Boolean
        get() = machine.isRunRequested

    val wantsTunnel: Boolean
        get() = prefs.getBoolean(KEY_WANTS_TUNNEL, false)

    /** Android's always-on and sticky restarts must not undo the user's own stop. */
    val userStopped: Boolean
        get() = prefs.getBoolean(KEY_USER_STOPPED, false)

    val killSwitchEnabled: Boolean
        get() = machine.killSwitchEnabled

    fun attachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.attachFlutterEngine(engine)

    fun detachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.detachFlutterEngine(engine)

    suspend fun handleToggleAction() {
        if (isRunRequested) {
            AutoTunnelState.onUserStop()
            markStopped(byUser = true)
        } else {
            clearUserStop()
        }
        machine.handleToggleAction()
    }

    suspend fun handleStartAction() {
        clearUserStop()
        machine.handleStartAction()
    }

    suspend fun handleStopAction() {
        AutoTunnelState.onUserStop()
        markStopped(byUser = true)
        machine.handleStopAction()
    }

    /** A stop auto-tunnel makes, which isn't the user's. */
    suspend fun handleSwitchStop() {
        markStopped(byUser = false)
        machine.handleStopAction()
    }

    suspend fun handleVpnRevokeAction() {
        markStopped(byUser = true)
        machine.handleVpnRevokeAction()
    }

    suspend fun awaitRunTime(): Long = machine.awaitRunTime()

    fun requestStart(): Deferred<Boolean> {
        clearUserStop()
        return machine.requestStart()
    }

    // Flutter answers a stop Android reported with its own; only one that ends a run is the user's.
    fun requestStop(): Deferred<Boolean> {
        AutoTunnelState.onUserStop()
        if (isRunRequested) markStopped(byUser = true)
        return machine.requestStop()
    }

    fun syncSharedState(state: SharedState) = machine.syncSharedState(state)

    internal fun captureRequestToken(): RunRequest = machine.captureRequestToken()

    internal suspend fun handleServiceLost(token: RunRequest) = machine.handleServiceLost(token)

    // A start that never came up, such as a declined permission, must not block or retry.
    internal fun onStarted() {
        if (isRunRequested && !userStopped) {
            prefs.edit().putBoolean(KEY_WANTS_TUNNEL, true).apply()
        }
    }

    private fun clearUserStop() {
        prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
    }

    private fun markStopped(byUser: Boolean) {
        prefs.edit()
            .putBoolean(KEY_WANTS_TUNNEL, false)
            .apply { if (byUser) putBoolean(KEY_USER_STOPPED, true) }
            .apply()
        KillSwitch.evaluate()
        AutoReconnect.evaluate()
    }

    private const val PREFS = "quartz_service_intent"
    private const val KEY_WANTS_TUNNEL = "wantsTunnel"
    private const val KEY_USER_STOPPED = "userStopped"
}
