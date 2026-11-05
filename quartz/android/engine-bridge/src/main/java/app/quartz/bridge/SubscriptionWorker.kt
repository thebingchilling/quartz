package app.quartz.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.quartz.core.data.EngineProfileDao
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Hourly check that updates every subscription whose interval is due (the provider's
 * `profile-update-interval`, else [DEFAULT_INTERVAL_HOURS]), skipping Wi-Fi-only ones on other
 * networks. A running profile that changed is restarted on the new one.
 */
class SubscriptionWorker(
    context: Context,
    params: WorkerParameters,
    private val profileDao: EngineProfileDao,
    private val subscriptions: Subscriptions,
    private val tunnelProvider: TunnelProvider,
    private val tunnelCoordinator: TunnelCoordinator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val onWifi = isOnWifi()
        var restart = false
        for (profile in profileDao.getAll()) {
            if (profile.subscriptionUrl == null) continue
            val interval =
                TimeUnit.HOURS.toMillis(
                    (profile.updateIntervalHours ?: DEFAULT_INTERVAL_HOURS).toLong()
                )
            if (interval <= 0 || now - (profile.lastUpdatedAt ?: 0) < interval) continue
            if (profile.wifiOnly && !onWifi) continue
            subscriptions
                .update(profile.tunnelId)
                .onSuccess {
                    if (profile.tunnelId in tunnelProvider.backendStatus.value.activeTunnels)
                        restart = true
                }
                .onFailure { Timber.w(it, "Subscription update failed for ${profile.tunnelId}") }
        }
        if (restart) tunnelCoordinator.restartActiveTunnels()
        return Result.success()
    }

    private fun isOnWifi(): Boolean {
        val manager =
            applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        // With the VPN up the active network is the VPN; it doesn't say what's underneath, so
        // treat "not metered" as Wi-Fi-like.
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 24
        private const val WORK_NAME = "quartz-subscriptions"

        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SubscriptionWorker>(1, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build(),
                )
        }
    }
}
