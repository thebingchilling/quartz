package com.follow.clash.autotunnel

import com.follow.clash.RunState
import com.follow.clash.ServiceState
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

/**
 * Blocks every app while the rule in effect has a kill switch and its tunnel isn't up: while it
 * connects, drops, or after the user stops it, until a tunnel is back or another rule takes over.
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
                    if (seenStopped) AutoTunnelState.onStarted()
                } else if (state == RunState.STOPPED) {
                    seenStopped = true
                }
                evaluate()
            }
        }
    }

    @Synchronized
    fun evaluate() {
        val context = GlobalState.application
        val state = ServiceState.runState.value
        val block = AutoTunnelRules.blocksAll(
            AutoTunnel.settings(context),
            AutoTunnelState.active,
            running = state == RunState.STARTED || state == RunState.STARTING,
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
