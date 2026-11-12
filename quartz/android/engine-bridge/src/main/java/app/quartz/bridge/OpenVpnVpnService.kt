package app.quartz.bridge

import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import app.quartz.engine.openvpn.OpenVpnEngine
import app.quartz.engine.openvpn.OpenVpnNative
import app.quartz.engine.openvpn.OpenVpnSession
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * The VpnService for OpenVPN profiles: runs one openvpn3 session and builds the TUN from its
 * tun-builder callbacks (addresses, routes, DNS and MTU the server pushes). openvpn3 protects its
 * own socket through [protect]. Not a foreground service; [EngineCompanionService] holds the
 * notification, as for Clash.
 */
class OpenVpnVpnService : VpnService() {
    private val runner: OpenVpnRunner by inject()
    private val engine: OpenVpnEngine by inject()
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + app.quartz.core.QuartzErrors.handler("openvpn"))
    private val mutex = Mutex()
    @Volatile private var session: OpenVpnSession? = null
    /** Bumped on every start, so a previous session ending late can't touch the new one. */
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { start() }
            ACTION_STOP -> scope.launch { stop() }
        }
        return START_NOT_STICKY
    }

    private suspend fun start() = mutex.withLock {
        val request = runner.takeRequest() ?: return@withLock
        runCatching { session?.stop() }
        session = null
        if (prepare(this) != null) {
            return@withLock fail(request.tunnelId, "VPN permission missing or revoked")
        }
        val callback = TunCallback(request.tunnelId, request.name)
        val current = ++generation
        runCatching {
                engine.start(
                    request.profile,
                    request.credentials?.username,
                    request.credentials?.password,
                    callback,
                ) { error -> scope.launch { onEnded(current, request.tunnelId, callback, error) } }
            }
            .onSuccess { session = it }
            .onFailure { fail(request.tunnelId, it.message ?: "OpenVPN failed to start") }
    }

    private suspend fun onEnded(
        ended: Int,
        tunnelId: Int,
        callback: TunCallback,
        error: String?,
    ) =
        mutex.withLock {
            val message = callback.fatalError ?: error
            if (message != null) Timber.e("OpenVPN: $message")
            if (ended != generation) return@withLock // a newer session runs now
            if (callback.connected || message == null) runner.onStopped()
            else runner.onFailed(tunnelId, message)
            session = null
            stopSelf()
        }

    private fun fail(tunnelId: Int, message: String) {
        Timber.e("OpenVPN: $message")
        runner.onFailed(tunnelId, message)
        stopSelf()
    }

    private suspend fun stop() = mutex.withLock {
        val running = session
        session = null
        if (running == null) {
            runner.onStopped()
            stopSelf()
        } else runCatching { running.stop() } // onEnded follows and stops the service
            .onFailure {
                runner.onStopped()
                stopSelf()
            }
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked permission in system settings.
        scope.launch { stop() }
    }

    override fun onDestroy() {
        runCatching { session?.stop() }
        session = null
        runner.onStopped()
        scope.cancel()
        super.onDestroy()
    }

    /** openvpn3's tun builder, on the session's thread: collects settings, then establishes. */
    private inner class TunCallback(private val tunnelId: Int, private val name: String) :
        OpenVpnNative.Callback {
        private var builder: Builder? = null
        @Volatile var connected = false
        @Volatile var fatalError: String? = null

        override fun tunNew(): Boolean {
            builder =
                Builder().apply {
                    setSession(name)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
                }
            return true
        }

        override fun tunSetRemoteAddress(address: String, ipv6: Boolean) = true

        override fun tunAddAddress(
            address: String,
            prefixLength: Int,
            gateway: String,
            ipv6: Boolean,
            net30: Boolean,
        ) = add { addAddress(address, prefixLength) }

        override fun tunRerouteGw(ipv4: Boolean, ipv6: Boolean, flags: Int) = add {
            if (ipv4) addRoute("0.0.0.0", 0)
            if (ipv6) addRoute("::", 0)
        }

        override fun tunAddRoute(address: String, prefixLength: Int, metric: Int, ipv6: Boolean) =
            add { addRoute(network(address, prefixLength), prefixLength) }

        override fun tunExcludeRoute(
            address: String,
            prefixLength: Int,
            metric: Int,
            ipv6: Boolean,
        ): Boolean {
            // Android 13+ can exclude a route; older versions keep it in the tunnel.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return add {
                excludeRoute(IpPrefix(InetAddress.getByName(network(address, prefixLength)), prefixLength))
            }
        }

        override fun tunAddDns(address: String, ipv6: Boolean) = add { addDnsServer(address) }

        override fun tunAddSearchDomain(domain: String) = add { addSearchDomain(domain) }

        override fun tunSetMtu(mtu: Int) = add { setMtu(mtu) }

        override fun tunSetSessionName(name: String) = true

        override fun tunEstablish(): Int {
            val tun = runCatching { builder?.establish() }.getOrNull() ?: return -1
            builder = null
            // openvpn3 owns the fd from here and closes it when the session ends.
            return tun.detachFd()
        }

        override fun tunTeardown(disconnect: Boolean) = Unit

        override fun protect(fd: Int): Boolean = this@OpenVpnVpnService.protect(fd)

        override fun event(name: String, info: String, error: Boolean, fatal: Boolean) {
            Timber.i("OpenVPN event $name ${info.take(200)}")
            when (name) {
                "CONNECTED" -> {
                    connected = true
                    runner.onConnected(tunnelId)
                }
                "RECONNECTING" -> runner.onReconnecting(tunnelId)
            }
            if (fatal) fatalError = listOf(name, info).filter { it.isNotBlank() }.joinToString(": ")
        }

        override fun log(text: String) = Timber.d("openvpn3: ${text.trimEnd()}")

        private inline fun add(block: Builder.() -> Unit): Boolean {
            val target = builder ?: return false
            return runCatching { target.block() }
                .onFailure { Timber.w(it, "OpenVPN: TUN setting refused") }
                .isSuccess
        }
    }

    companion object {
        const val ACTION_START = "app.quartz.openvpn.START"
        const val ACTION_STOP = "app.quartz.openvpn.STOP"
    }
}

/** The network address of [address]/[prefix] (Android's VpnService.Builder rejects host bits). */
internal fun network(address: String, prefix: Int): String {
    val bytes = InetAddress.getByName(address).address
    for (i in bytes.indices) {
        val bits = (prefix - i * 8).coerceIn(0, 8)
        bytes[i] = (bytes[i].toInt() and (0xFF shl (8 - bits))).toByte()
    }
    return InetAddress.getByAddress(bytes).hostAddress ?: address
}
