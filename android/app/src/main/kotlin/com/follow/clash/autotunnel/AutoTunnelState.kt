package com.follow.clash.autotunnel

import com.follow.clash.ServiceState

/** Starts and stops the rules make run inside [auto]; any other is the user's. */
object AutoTunnelState {
    @Volatile
    var active: String? = null

    @Volatile
    var manual = false
        private set

    @Volatile
    private var applying = false

    @Volatile
    private var autoStart = false

    @Volatile
    var rejudge: (() -> Unit)? = null

    suspend fun auto(connect: Boolean, block: suspend () -> Unit) {
        if (connect && !ServiceState.isRunRequested) autoStart = true
        applying = true
        try {
            block()
        } finally {
            applying = false
        }
    }

    fun expectStart() {
        autoStart = true
    }

    fun onStarted() {
        when {
            autoStart -> autoStart = false
            applying -> Unit
            else -> {
                manual = true
                active = null
            }
        }
    }

    fun onUserStop() {
        if (applying) return
        autoStart = false
        manual = false
        rejudge?.invoke()
    }
}
