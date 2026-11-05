package app.quartz.warp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.quartz.core.data.TunnelMeta
import app.quartz.core.data.TunnelMetaDao
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Expiry enforcement (FORK.md §9): at each tunnel's expiry, stop it if running, notify, and
 * optionally revoke the registration; warn 24 h before. One WorkManager job is kept scheduled for
 * the next of these moments. WorkManager survives reboots and recomputes delays on clock and
 * timezone changes; [check] also runs on every app start.
 */
class WarpExpiry(
    private val context: Context,
    private val metaDao: TunnelMetaDao,
    private val tunnelRepository: TunnelRepository,
    private val tunnelProvider: TunnelProvider,
    private val tunnelCoordinator: Lazy<TunnelCoordinator>,
    private val settingsRepository: GeneralSettingRepository,
    private val notificationService: NotificationService,
    private val warpRepository: Lazy<WarpRepository>,
) {
    private val mutex = Mutex()

    suspend fun check(now: Long = System.currentTimeMillis()) = mutex.withLock {
        for (meta in metaDao.getAll()) {
            val expiresAt = meta.expiresAt ?: continue
            when {
                expiresAt <= now && !meta.handledExpiry -> expire(meta)
                expiresAt > now && expiresAt - WARNING_MS <= now && !meta.warnedExpiring ->
                    warn(meta, expiresAt - now)
            }
        }
        schedule(metaDao.getAll(), now)
    }

    fun schedule(metas: List<TunnelMeta>, now: Long = System.currentTimeMillis()) {
        val next =
            metas
                .flatMap { meta ->
                    val at = meta.expiresAt ?: return@flatMap emptyList()
                    listOfNotNull(
                        (at - WARNING_MS).takeIf { !meta.warnedExpiring && it > now },
                        at.takeIf { !meta.handledExpiry },
                    )
                }
                .minOrNull()
        val workManager = WorkManager.getInstance(context)
        if (next == null) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WarpExpiryWorker>()
                .setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private suspend fun expire(meta: TunnelMeta) {
        val name = tunnelRepository.getById(meta.tunnelId)?.name ?: return
        if (meta.tunnelId in tunnelProvider.backendStatus.value.activeTunnels) {
            tunnelCoordinator.value.stopTunnel(meta.tunnelId, TunnelActionSource.AUTO_TUNNEL)
        }
        val lockdown = settingsRepository.flow.first().tunnelMode == TunnelMode.LOCK_DOWN
        notify(
            meta.tunnelId,
            context.getString(R.string.quartz_warp_expired_title, name),
            context.getString(
                if (lockdown) R.string.quartz_warp_expired_lockdown
                else R.string.quartz_warp_expired_text
            ),
        )
        metaDao.upsert(meta.copy(warnedExpiring = true, handledExpiry = true))
        if (meta.revokeOnExpiry) warpRepository.value.revoke(meta)
    }

    private suspend fun warn(meta: TunnelMeta, remainingMs: Long) {
        val name = tunnelRepository.getById(meta.tunnelId)?.name ?: return
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs).coerceAtLeast(1).toInt()
        notify(
            meta.tunnelId,
            context.getString(R.string.quartz_warp_expiring_title, name),
            context.resources.getQuantityString(R.plurals.quartz_warp_expiring_text, hours, hours),
        )
        metaDao.upsert(meta.copy(warnedExpiring = true))
    }

    private fun notify(tunnelId: Int, title: String, text: String) {
        val notification =
            notificationService.createNotification(
                channel = NotificationChannels.App,
                title = title,
                description = text,
                onlyAlertOnce = false,
            )
        notificationService.show(NOTIFICATION_ID_BASE + tunnelId, notification)
    }

    companion object {
        const val WORK_NAME = "quartz-warp-expiry"
        const val WARNING_MS = 24 * 60 * 60 * 1000L
        private const val NOTIFICATION_ID_BASE = 7_000
    }
}

class WarpExpiryWorker(
    context: Context,
    params: WorkerParameters,
    private val expiry: WarpExpiry,
) : CoroutineWorker(context, params) {
    // Rescheduling with REPLACE may cancel this run; finish it, the check is idempotent.
    override suspend fun doWork(): Result =
        withContext(NonCancellable) {
            expiry.check()
            Result.success()
        }
}
