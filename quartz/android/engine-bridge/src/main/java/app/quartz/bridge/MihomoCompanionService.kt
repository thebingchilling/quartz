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
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground companion while a Clash profile runs: shows upstream's tunnel notification (built by
 * upstream's AppProvider from the merged status, so it looks like any tunnel's) and keeps the
 * process alive, as upstream's VpnCompanionService does for WireGuard.
 */
class MihomoCompanionService : Service() {
    private val appProvider: AppProvider by inject()
    private val tunnelProvider: TunnelProvider by inject()
    private val runner: MihomoRunner by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notifications: Job? = null

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
        // The VpnService is started from here, once this service is in the foreground: started
        // straight from MihomoRunner it threw (and crashed the app) whenever the app was in the
        // background, e.g. from the quick settings tile or auto-tunnel.
        if (intent?.action == ACTION_START_VPN) {
            runCatching {
                    startService(
                        Intent(this, MihomoVpnService::class.java)
                            .setAction(MihomoVpnService.ACTION_START)
                    )
                }
                .onFailure { runner.onServiceStartFailed(it) }
        }
        if (notifications?.isActive == true) return START_NOT_STICKY
        notifications = scope.launch {
            tunnelProvider.backendStatus.collect { status ->
                if (status.activeTunnels.isNotEmpty()) {
                    val notification = appProvider.buildVpnPersistentNotification(status)
                    ServiceCompat.startForeground(
                        this@MihomoCompanionService,
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
        const val ACTION_START_VPN = "app.quartz.mihomo.START_VPN"
    }
}
