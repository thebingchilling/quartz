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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        scope.launch {
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
}
