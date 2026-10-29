package com.zaneschepke.wireguardautotunnel.core.wifi

/**
 * Location permission for reading the macOS Wi-Fi name.
 */
enum class WifiAuthorization(val code: Int) {
    NotDetermined(0),
    Restricted(1),
    Denied(2),
    AuthorizedAlways(3),
    AuthorizedWhenInUse(4),
    ServicesOff(5),
    Unsupported(-1);

    val granted: Boolean
        get() = this == AuthorizedAlways || this == AuthorizedWhenInUse

    companion object {
        fun fromCode(code: Int): WifiAuthorization =
            entries.firstOrNull { it.code == code } ?: Unsupported
    }
}

/**
 * Wi-Fi name reader backed by CoreWLAN in this process.
 *
 * The Wi-Fi LaunchAgent calls [authorization], [requestAuthorization], [associated], and
 * [currentSsid]. The desktop app does not prompt. It opens Location Settings. [waitForEvents] runs
 * the main run loop so a Location grant delivered to this process wakes the agent, which then reads
 * the name. [setObserved] writes the name into this process's network monitor, so only the daemon
 * may call it.
 */
object WifiSsidNative {
    @JvmStatic external fun authorization(): Int

    @JvmStatic external fun requestAuthorization()

    @JvmStatic external fun associated(): Boolean

    @JvmStatic external fun currentSsid(): String

    /**
     * Waits on the main thread until [timeoutSeconds] elapses, [wakeWait] is called, or Location
     * authorization changes. A negative timeout waits until one of those. Returns true when
     * authorization changed. Call it on the main thread.
     */
    @JvmStatic external fun waitForEvents(timeoutSeconds: Double): Boolean

    /** Wakes a thread blocked in [waitForEvents]. Safe to call from another thread. */
    @JvmStatic external fun wakeWait()

    @JvmStatic external fun setObserved(ssid: String)

    fun authorizationStatus(): WifiAuthorization = WifiAuthorization.fromCode(authorization())
}
