package app.quartz.engine.openvpn

/** What an OpenVPN profile needs before it can connect (checked without connecting). */
sealed interface OpenVpnCheck {
    data object Ready : OpenVpnCheck

    data object NeedsCredentials : OpenVpnCheck

    data class Invalid(val message: String) : OpenVpnCheck
}

/**
 * The openvpn3 engine (FORK.md §3): parses profiles and runs one session at a time on a thread of
 * its own. The TUN is built by the [OpenVpnNative.Callback] the caller (the VpnService) passes in.
 */
class OpenVpnEngine {

    val version: String?
        get() = OpenVpnNative.load().map { OpenVpnNative.nativeVersion() }.getOrNull()

    fun check(profile: String): OpenVpnCheck {
        OpenVpnNative.load().onFailure {
            return OpenVpnCheck.Invalid("OpenVPN engine missing: ${it.message}")
        }
        val result = OpenVpnNative.nativeCheck(profile)
        return when {
            result.isEmpty() -> OpenVpnCheck.Ready
            result == "creds" -> OpenVpnCheck.NeedsCredentials
            else -> OpenVpnCheck.Invalid(result.removePrefix("error:"))
        }
    }

    /**
     * Parses [profile], gives the credentials and starts connecting on a new thread. Throws if the
     * profile can't be used; [onEnded] runs once the session is over (null error: stopped).
     */
    fun start(
        profile: String,
        username: String?,
        password: String?,
        callback: OpenVpnNative.Callback,
        onEnded: (error: String?) -> Unit,
    ): OpenVpnSession {
        OpenVpnNative.load().getOrThrow()
        val handle = OpenVpnNative.nativeCreate(callback)
        if (handle == 0L) throw IllegalStateException("Couldn't create an OpenVPN session")
        OpenVpnNative.nativeConfigure(handle, profile, username, password)?.let { error ->
            OpenVpnNative.nativeDestroy(handle)
            throw IllegalArgumentException(error)
        }
        return OpenVpnSession(handle, onEnded).also { it.run() }
    }
}

/** One running openvpn3 session. */
class OpenVpnSession internal constructor(
    private val handle: Long,
    private val onEnded: (String?) -> Unit,
) {
    private val lock = Any()
    @Volatile private var ended = false
    @Volatile private var stopRequested = false

    internal fun run() {
        Thread(
                {
                    val error = runCatching { OpenVpnNative.nativeConnect(handle) }
                        .getOrElse { it.message ?: "OpenVPN failed" }
                    synchronized(lock) {
                        ended = true
                        OpenVpnNative.nativeDestroy(handle)
                    }
                    onEnded(if (stopRequested) null else error)
                },
                "openvpn3",
            )
            .start()
    }

    /** Asks the session to disconnect; [OpenVpnEngine.start]'s onEnded follows. */
    fun stop() =
        synchronized(lock) {
            stopRequested = true
            if (!ended) OpenVpnNative.nativeStop(handle)
        }

    /** Bytes received and sent through the tunnel. */
    fun traffic(): Pair<Long, Long> =
        synchronized(lock) {
            if (ended) 0L to 0L
            else OpenVpnNative.nativeTraffic(handle).let { it[0] to it[1] }
        }

    val isRunning: Boolean
        get() = !ended
}
