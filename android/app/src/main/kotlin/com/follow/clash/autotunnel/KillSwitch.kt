package com.follow.clash.autotunnel

import com.follow.clash.RunState
import com.follow.clash.ServiceState
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

/**
 * Blocks every app while a tunnel is wanted and isn't up, so nothing leaks if it drops. A manual
 * stop or an auto-tunnel disconnect means no tunnel is wanted, which releases it.
 */
object KillSwitch {
    @Volatile
    private var manualOff = false

    @Volatile
    private var tunnelWanted = false

    @Volatile
    private var blocking: List<String>? = null
    private var released = true
    private var watching = false

    @Synchronized
    fun watch() {
        if (watching) return
        watching = true
        GlobalState.launch {
            ServiceState.runState.collect { state ->
                if (state == RunState.STARTED) {
                    manualOff = false
                    tunnelWanted = true
                }
                evaluate()
            }
        }
    }

    fun onManualStop() {
        manualOff = true
    }

    fun onDecision(connect: Boolean) {
        tunnelWanted = connect
        if (connect) manualOff = false
        evaluate()
    }

    /** What to block now: null for nothing, an empty list for every app. */
    internal fun decide(
        settings: AutoTunnelSettings,
        state: RunState,
        manualOff: Boolean,
        tunnelWanted: Boolean,
    ): List<String>? = when {
        state == RunState.STARTED || state == RunState.STARTING -> null
        !manualOff && AutoTunnelRules.blocksAll(settings, tunnelWanted) -> emptyList()
        else -> null
    }

    @Synchronized
    fun evaluate() {
        val context = GlobalState.application
        val next = decide(
            AutoTunnel.settings(context),
            ServiceState.runState.value,
            manualOff,
            tunnelWanted,
        )
        if (next == null) {
            if (!released) KillSwitchService.release(context)
            released = true
            blocking = null
            return
        }
        if (!released && next == blocking) return
        released = false
        blocking = next
        KillSwitchService.block(context, next.ifEmpty { null })
    }
}
