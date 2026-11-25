package com.follow.clash.autotunnel

import com.follow.clash.AutoReconnect
import com.follow.clash.RunState
import com.follow.clash.ServiceState
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

/**
 * Blocks every app while a tunnel someone asked for isn't up: with the kill switch setting on, any
 * that dropped on its own, and the tunnel of a rule with a kill switch. A tunnel the user stopped
 * never blocks.
 */
object KillSwitch {
    private var released = true
    private var watching = false

    @Synchronized
    fun watch() {
        if (watching) return
        watching = true
        GlobalState.launch {
            var seenStopped = false
            ServiceState.runState.collect { state ->
                if (state == RunState.STARTED) {
                    ServiceState.onStarted()
                    if (seenStopped) AutoTunnelState.onStarted()
                } else if (state == RunState.STOPPED) {
                    seenStopped = true
                }
                evaluate()
                AutoReconnect.evaluate()
            }
        }
    }

    @Synchronized
    fun evaluate() {
        val context = GlobalState.application
        val state = ServiceState.runState.value
        val running = state == RunState.STARTED || state == RunState.STARTING
        val dropped = ServiceState.wantsTunnel && ServiceState.killSwitchEnabled && !running
        val block = dropped || (
            !ServiceState.userStopped &&
                AutoTunnelRules.blocksAll(AutoTunnel.settings(context), AutoTunnelState.active, running)
            )
        if (!block) {
            if (!released) KillSwitchService.release(context)
            released = true
            return
        }
        if (!released) return
        released = false
        KillSwitchService.block(context, null)
    }
}
