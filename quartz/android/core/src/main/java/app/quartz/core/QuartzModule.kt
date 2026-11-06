package app.quartz.core

import androidx.room.Room
import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MultiEngineTunnelProvider
import app.quartz.bridge.SubscriptionWorker
import app.quartz.bridge.Subscriptions
import app.quartz.clash.ui.ClashViewModel
import app.quartz.core.data.ForkDatabase
import app.quartz.engine.mihomo.MihomoEngine
import app.quartz.warp.CloudflareWarpApi
import app.quartz.warp.WarpApi
import app.quartz.warp.WarpExpiry
import app.quartz.warp.WarpExpiryWorker
import app.quartz.warp.WarpRepository
import app.quartz.warp.ui.WarpViewModel
import com.wgtunnel.backend.Backend
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelBackendProvider
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.di.Dispatcher
import com.zaneschepke.wireguardautotunnel.di.Scope
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Quartz's Koin module, loaded after upstream's (one line in WireGuardAutoTunnel.kt), so its
 * definitions override upstream's where they share a type.
 */
val quartzModule = module {
    single {
        Room.databaseBuilder(androidContext(), ForkDatabase::class.java, ForkDatabase.NAME).build()
    }
    single { get<ForkDatabase>().tunnelMetaDao() }
    single { get<ForkDatabase>().engineProfileDao() }

    // Overrides upstream's binding in TunnelModule.kt: upstream's provider handles WireGuard,
    // wrapped by the one that routes Clash tunnels to mihomo (FORK.md §3).
    single<TunnelProvider> {
        MultiEngineTunnelProvider(
            wireGuard =
                TunnelBackendProvider(
                    get<Backend>(),
                    get(named(Scope.APPLICATION)),
                    get(named(Dispatcher.IO)),
                ),
            metaDao = get(),
            profiles = get(),
            mihomo = get(),
            scope = get(named(Scope.APPLICATION)),
        )
    }

    single { MihomoEngine(File(androidContext().filesDir, "mihomo")) }
    single { MihomoRunner(androidContext()) }
    single { ClashProfiles(androidContext(), get(), get(), get(named(Dispatcher.IO))) }
    single { Subscriptions(get(), get(), get(), get(named(Dispatcher.IO))) }
    workerOf(::SubscriptionWorker)
    viewModel { (tunnelId: Int) ->
        ClashViewModel(tunnelId, get(), get(), get(), get(), get(), get(), get())
    }

    single<WarpApi> { CloudflareWarpApi() }
    single {
        WarpRepository(
            context = androidContext(),
            api = get(),
            metaDao = get(),
            tunnelRepository = get(),
            tunnelProvider = get(),
            tunnelCoordinator = lazy { get() },
            ioDispatcher = get(named(Dispatcher.IO)),
        )
    }
    single {
        WarpExpiry(
            context = androidContext(),
            metaDao = get(),
            tunnelRepository = get(),
            tunnelProvider = get(),
            tunnelCoordinator = lazy { get() },
            settingsRepository = get(),
            notificationService = get(),
            warpRepository = lazy { get() },
        )
    }
    workerOf(::WarpExpiryWorker)
    viewModelOf(::WarpViewModel)

    single(createdAtStart = true) {
        SubscriptionWorker.schedule(androidContext())
        QuartzSync(get(named(Scope.APPLICATION)), get(), get(), get(), get(), get())
    }
}
