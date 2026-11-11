package app.quartz.bridge

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.zaneschepke.wireguardautotunnel.core.tunnel.AppProvider
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground companion while a Clash (mihomo) or OpenVPN profile runs: shows upstream's tunnel
 * notification (built by upstream's AppProvider from the merged status, so it looks like any
 * tunnel's) and keeps the process alive, as upstream's VpnCompanionService does for WireGuard.
 */
class EngineCompanionService : Service() {
    private val appProvider: AppProvider by inject()
    private val tunnelProvider: TunnelProvider by inject()
    private val mihomo: MihomoRunner by inject()
    private val openVpn: OpenVpnRunner by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notifications: Job? = null
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NotificationService.VPN_NOTIFICATION_ID,
            appProvider.vpnInitNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
        // The engine's VpnService is started from here, once this service is in the foreground:
        // started straight from a runner it threw (and crashed the app) whenever the app was in
        // the background, e.g. from the quick settings tile or auto-tunnel.
        if (intent?.action == ACTION_START_VPN) {
            val openVpnStart = intent.getStringExtra(EXTRA_ENGINE) == ENGINE_OPENVPN
            runCatching {
                    startService(
                        if (openVpnStart)
                            Intent(this, OpenVpnVpnService::class.java)
                                .setAction(OpenVpnVpnService.ACTION_START)
                        else
                            Intent(this, MihomoVpnService::class.java)
                                .setAction(MihomoVpnService.ACTION_START)
                    )
                }
                .onFailure {
                    if (openVpnStart) openVpn.onServiceStartFailed(it)
                    else mihomo.onServiceStartFailed(it)
                }
        }
        lastStartId = startId
        if (watcher?.isActive != true) {
            // Stops itself when no engine is left running, always after startForeground.
            watcher = scope.launch {
                combine(mihomo.state, openVpn.state) { m, o ->
                        (m is MihomoState.Idle || m is MihomoState.Failed) &&
                            (o is OpenVpnState.Idle || o is OpenVpnState.Failed)
                    }
                    .collect { idle ->
                        if (idle) {
                            ServiceCompat.stopForeground(
                                this@EngineCompanionService,
                                ServiceCompat.STOP_FOREGROUND_REMOVE,
                            )
                            stopSelfResult(lastStartId)
                        }
                    }
            }
        }
        if (notifications?.isActive == true) return START_NOT_STICKY
        notifications = scope.launch {
            tunnelProvider.backendStatus.collect { status ->
                if (status.activeTunnels.isNotEmpty()) {
                    val notification = appProvider.buildVpnPersistentNotification(status)
                    ServiceCompat.startForeground(
                        this@EngineCompanionService,
                        NotificationService.VPN_NOTIFICATION_ID,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        else 0,
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_VPN = "app.quartz.engine.START_VPN"
        const val EXTRA_ENGINE = "engine"
        const val ENGINE_OPENVPN = "openvpn"
    }
}
