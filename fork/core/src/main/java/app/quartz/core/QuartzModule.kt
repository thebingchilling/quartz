package app.quartz.core

import androidx.room.Room
import app.quartz.core.data.ForkDatabase
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
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
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

    // Overrides upstream's binding in TunnelModule.kt with the same provider, wrapped.
    single<TunnelProvider> {
        ExpiryGuardTunnelProvider(
            TunnelBackendProvider(
                get<Backend>(),
                get(named(Scope.APPLICATION)),
                get(named(Dispatcher.IO)),
            ),
            get(),
        )
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
        QuartzSync(get(named(Scope.APPLICATION)), get(), get(), get(), get())
    }
}
