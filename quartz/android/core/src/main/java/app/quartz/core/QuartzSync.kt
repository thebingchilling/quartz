package app.quartz.core

import android.content.Context
import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.OpenVpnProfiles
import app.quartz.core.data.TunnelMetaDao
import app.quartz.warp.WarpExpiry
import app.quartz.warp.WarpRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

/** Background work started with the app: keeps fork data in step with upstream's tunnels. */
class QuartzSync(
    context: Context,
    scope: CoroutineScope,
    tunnelRepository: TunnelRepository,
    metaDao: TunnelMetaDao,
    warpRepository: WarpRepository,
    warpExpiry: WarpExpiry,
    clashProfiles: ClashProfiles,
    openVpnProfiles: OpenVpnProfiles,
    settingsRepository: GeneralSettingRepository,
    appRules: app.quartz.apprules.AppRules,
) {
    init {
        scope.launch {
            tunnelRepository.userTunnelsFlow.collect { tunnels ->
                val ids = tunnels.map { it.id }.toSet()
                runCatching {
                        clashProfiles.removeOrphans(ids)
                        openVpnProfiles.removeOrphans(ids)
                        appRules.removeTunnelsNotIn(ids)
                    }
                    .onFailure { Timber.e(it, "Engine profile cleanup failed") }
                runCatching { warpRepository.sync(tunnels) }
                    .onFailure { Timber.e(it, "WARP sync failed") }
            }
        }
        scope.launch {
            // Quartz defaults to the Dynamic theme: once, an install still on upstream's default
            // (Automatic) moves to it. Choosing Automatic again later sticks.
            runCatching {
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (!prefs.getBoolean(DYNAMIC_THEME_APPLIED, false)) {
                        if (settingsRepository.getGeneralSettings().theme == Theme.AUTOMATIC)
                            settingsRepository.updateTheme(Theme.DYNAMIC)
                        prefs.edit().putBoolean(DYNAMIC_THEME_APPLIED, true).apply()
                    }
                }
                .onFailure { Timber.e(it, "Theme default failed") }
        }
        scope.launch {
            runCatching { warpExpiry.check() }.onFailure { Timber.e(it, "Expiry check failed") }
            metaDao.flow().drop(1).collect {
                runCatching { warpExpiry.schedule(it) }.onFailure { e -> Timber.e(e, "Expiry schedule failed") }
            }
        }
    }

    private companion object {
        const val PREFS = "quartz"
        const val DYNAMIC_THEME_APPLIED = "dynamic_theme_applied"
    }
}
