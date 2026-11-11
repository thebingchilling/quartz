package app.quartz.engine.openvpn

/**
 * JNI bindings to libovpn3.so (quartz/android/engine-openvpn/cpp/ovpn_jni.cpp): the openvpn3 core's
 * client API. One handle is one session: create, configure, connect (blocks until it ends) from a
 * worker thread, stop from any thread, destroy once connect returned.
 */
object OpenVpnNative {
    /** Callbacks from openvpn3, on the thread running [nativeConnect]. Names match ovpn_jni.cpp. */
    interface Callback {
        fun tunNew(): Boolean

        fun tunSetRemoteAddress(address: String, ipv6: Boolean): Boolean

        fun tunAddAddress(
            address: String,
            prefixLength: Int,
            gateway: String,
            ipv6: Boolean,
            net30: Boolean,
        ): Boolean

        fun tunRerouteGw(ipv4: Boolean, ipv6: Boolean, flags: Int): Boolean

        fun tunAddRoute(address: String, prefixLength: Int, metric: Int, ipv6: Boolean): Boolean

        fun tunExcludeRoute(address: String, prefixLength: Int, metric: Int, ipv6: Boolean): Boolean

        fun tunAddDns(address: String, ipv6: Boolean): Boolean

        fun tunAddSearchDomain(domain: String): Boolean

        fun tunSetMtu(mtu: Int): Boolean

        fun tunSetSessionName(name: String): Boolean

        /** Builds the TUN and returns its fd (openvpn3 owns it from then on), or -1. */
        fun tunEstablish(): Int

        fun tunTeardown(disconnect: Boolean)

        /** VpnService.protect, so openvpn3's own socket bypasses the tunnel. */
        fun protect(fd: Int): Boolean

        fun event(name: String, info: String, error: Boolean, fatal: Boolean)

        fun log(text: String)
    }

    @Volatile private var loaded: Result<Unit>? = null

    /** Loads libovpn3.so once; a failure (missing ABI) is reported, not thrown at class load. */
    fun load(): Result<Unit> =
        loaded
            ?: synchronized(this) {
                loaded
                    ?: runCatching { System.loadLibrary("ovpn3") }.also { loaded = it }
            }

    @JvmStatic external fun nativeCreate(callback: Callback): Long

    @JvmStatic
    external fun nativeConfigure(
        handle: Long,
        profile: String,
        username: String?,
        password: String?,
    ): String?

    @JvmStatic external fun nativeConnect(handle: Long): String?

    @JvmStatic external fun nativeStop(handle: Long)

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeTraffic(handle: Long): LongArray

    @JvmStatic external fun nativeCheck(profile: String): String

    @JvmStatic external fun nativeVersion(): String
}
