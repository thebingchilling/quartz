package com.follow.clash.service.modules

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.follow.clash.common.receiveBroadcastFlow
import com.follow.clash.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** At a wake or Doze window, apps replace the connections a carrier dropped while the phone slept. */
internal class SleepModule(
    private val service: Service,
    private val scope: CoroutineScope,
) : ServiceModule {
    private val power by lazy { service.getSystemService<PowerManager>() }
    private var sleptAt: Long? = null

    override fun start() {
        scope.launch {
            service.receiveBroadcastFlow(exported = true) {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
                }
            }.onStart { emit(Intent()) }.collect { onPowerChanged() }
        }
    }

    @Synchronized
    private fun onPowerChanged() {
        val power = power ?: return
        val idle = power.isDeviceIdleMode ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && power.isDeviceLightIdleMode)
        val asleep = idle || !power.isInteractive
        val now = SystemClock.elapsedRealtime()
        val since = sleptAt
        if (!idle && since != null && now - since >= MIN_SLEEP_MILLIS) {
            Core.resetSleptConnections()
            sleptAt = null
        }
        when {
            !asleep -> sleptAt = null
            sleptAt == null -> {
                Core.markSleep()
                sleptAt = now
            }
        }
    }

    override fun stop() {
        sleptAt = null
    }
}

// Carriers keep an idle TCP mapping for minutes, so a shorter sleep loses none.
private const val MIN_SLEEP_MILLIS = 5 * 60_000L
