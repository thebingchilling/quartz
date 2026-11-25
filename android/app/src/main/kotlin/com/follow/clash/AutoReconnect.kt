package com.follow.clash

import com.follow.clash.common.GlobalState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Brings back a tunnel that dropped without anyone stopping it; the kill switch blocks meanwhile. */
object AutoReconnect {
    private val delaysMillis = listOf(2_000L, 5_000L, 10_000L, 30_000L)
    private const val MAX_DELAY_MILLIS = 60_000L
    private var job: Job? = null

    private val dropped: Boolean
        get() = ServiceState.wantsTunnel && ServiceState.runState.value == RunState.STOPPED

    @Synchronized
    fun evaluate() {
        if (!dropped) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = GlobalState.launch {
            var attempt = 0
            while (isActive && dropped) {
                delay(delaysMillis.getOrElse(attempt) { MAX_DELAY_MILLIS })
                attempt++
                if (!dropped) break
                GlobalState.log("Reconnecting after an unexpected drop, attempt $attempt")
                ServiceState.handleStartAction()
            }
        }
    }
}
