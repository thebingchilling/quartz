package app.quartz.bridge

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.quartz.engine.mihomo.MihomoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * The VpnService for Clash profiles: creates the TUN and hands its fd to mihomo, which reads
 * packets from it and protects its own sockets through [protect]. Not a foreground service (like
 * upstream's, for Android Auto); [EngineCompanionService] holds the notification.
 */
class MihomoVpnService : VpnService() {
    private val runner: MihomoRunner by inject()
    private val engine: MihomoEngine by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { start() }
            ACTION_STOP -> scope.launch { stop() }
        }
        return START_NOT_STICKY
    }

    private suspend fun start() = mutex.withLock {
        val request = runner.takeRequest() ?: return@withLock
        if (running) engine.stop()
        running = false
        // Parse first, before the TUN exists: parsing downloads missing geodata, which would hang
        // with the TUN up and unread. It also fails a broken profile fast.
        engine.validate(request.profile)?.let {
            return@withLock fail(request.tunnelId, it)
        }
        val tun =
            runCatching { buildTun(request.name) }.getOrNull()
                ?: return@withLock fail(request.tunnelId, "VPN permission missing or revoked")
        // mihomo owns the fd from here and closes it when it stops.
        val fd = tun.detachFd()
        runCatching { engine.start(request.profile, fd) { socket -> protect(socket) } }
            .onSuccess {
                running = true
                runner.onStarted(request.tunnelId, it)
            }
            .onFailure {
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                fail(request.tunnelId, it.message ?: "mihomo failed to start")
            }
    }

    private fun buildTun(session: String): ParcelFileDescriptor? =
        Builder()
            .setSession(session)
            .setMtu(MTU)
            .addAddress(TUN_V4, 30)
            .addAddress(TUN_V6, 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TUN_DNS)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
            .establish()

    private fun fail(tunnelId: Int, message: String) {
        Timber.e("mihomo: $message")
        runner.onFailed(tunnelId, message)
        stopSelf()
    }

    private suspend fun stop() = mutex.withLock {
        if (running) engine.stop()
        running = false
        runner.onStopped()
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked permission in system settings.
        scope.launch { stop() }
    }

    override fun onDestroy() {
        // mihomo can take a moment to shut down: never block the main thread on it (ANR).
        if (running) Thread { runBlocking { engine.stop() } }.start()
        running = false
        runner.onStopped()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.quartz.mihomo.START"
        const val ACTION_STOP = "app.quartz.mihomo.STOP"
        private const val MTU = 9000
        private const val TUN_V4 = "172.19.0.1"
        private const val TUN_V6 = "fdfe:dcba:9876::1"
        private const val TUN_DNS = "172.19.0.2"
    }
}
