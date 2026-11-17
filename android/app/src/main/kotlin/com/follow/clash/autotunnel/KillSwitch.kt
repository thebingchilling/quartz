package com.follow.clash.autotunnel

import com.follow.clash.RunState
import com.follow.clash.ServiceState
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.launch

/** A manual stop releases the tunnel kill switches, never the per-app ones. */
object KillSwitch {
    @Volatile
    private var manualOff = false

    @Volatile
    private var network = NetworkSnapshot(NetworkKind.NONE)

    @Volatile
    var nativeProfileId: Long? = null

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
                if (state == RunState.STARTED) manualOff = false
                evaluate()
            }
        }
    }

    fun onManualStop() {
        manualOff = true
    }

    fun onNetwork(snapshot: NetworkSnapshot) {
        val changed = snapshot.kind != network.kind ||
            snapshot.ssid != network.ssid ||
            snapshot.subscriptionId != network.subscriptionId
        network = snapshot
        if (changed) manualOff = false
        evaluate()
    }

    /** What to block now: null for nothing, an empty list for every app. */
    internal fun decide(
        settings: AutoTunnelSettings,
        state: RunState,
        network: NetworkSnapshot,
        manualOff: Boolean,
        profileId: Long?,
    ): List<String>? = when {
        state == RunState.STARTED || state == RunState.STARTING -> null
        !manualOff && AutoTunnelRules.blocksAll(settings, network, profileId) -> emptyList()
        settings.killSwitchApps.isNotEmpty() -> settings.killSwitchApps
        else -> null
    }

    @Synchronized
    fun evaluate() {
        val context = GlobalState.application
        val settings = AutoTunnel.settings(context)
        val next = decide(
            settings,
            ServiceState.runState.value,
            network,
            manualOff,
            nativeProfileId ?: settings.currentProfileId,
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
