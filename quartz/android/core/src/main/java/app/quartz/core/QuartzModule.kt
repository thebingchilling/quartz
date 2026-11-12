package app.quartz.core

import androidx.room.Room
import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MultiEngineTunnelProvider
import app.quartz.bridge.OpenVpnProfiles
import app.quartz.bridge.OpenVpnRunner
import app.quartz.bridge.SubscriptionWorker
import app.quartz.bridge.Subscriptions
import app.quartz.clash.ui.ClashTrees
import app.quartz.clash.ui.QuartzDelays
import app.quartz.clash.ui.QuartzRefresh
import app.quartz.core.ip.IpLookup
import app.quartz.core.ui.QuartzTunnelOrder
import app.quartz.core.data.ForkDatabase
import app.quartz.engine.mihomo.MihomoEngine
import app.quartz.engine.openvpn.OpenVpnEngine
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
    // Overrides upstream's application scope (AppModule.kt) with one that logs an uncaught
    // exception in a background job instead of letting it take the whole app down. Upstream's and
    // Quartz's background work (tunnel status, subscriptions, delay tests, Clash trees, WARP
    // expiry, auto-tunnel) all runs in this scope.
    single<kotlinx.coroutines.CoroutineScope>(named(Scope.APPLICATION)) {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Default +
                QuartzErrors.handler("app")
        )
    }
    single {
        Room.databaseBuilder(androidContext(), ForkDatabase::class.java, ForkDatabase.NAME).build()
    }
    single { get<ForkDatabase>().tunnelMetaDao() }
    single { get<ForkDatabase>().engineProfileDao() }

    // Overrides upstream's binding in TunnelModule.kt: upstream's provider handles WireGuard,
    // wrapped by the one that routes Clash tunnels to mihomo and OpenVPN to openvpn3 (FORK.md §3).
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
            openVpnProfiles = get(),
            openVpn = get(),
            selections = get(),
            scope = get(named(Scope.APPLICATION)),
        )
    }

    single { app.quartz.apprules.AppRules(androidContext(), get(named(Dispatcher.IO))) }
    single { OpenVpnEngine() }
    single { OpenVpnRunner(androidContext()) }
    single { OpenVpnProfiles(androidContext(), get(), get(), get(named(Dispatcher.IO))) }

    single { MihomoEngine(File(androidContext().filesDir, "mihomo")) }
    single { MihomoRunner(androidContext()) }
    single { app.quartz.bridge.ClashSelections(androidContext()) }
    single { ClashProfiles(androidContext(), get(), get(), get(named(Dispatcher.IO))) }
    single { Subscriptions(get(), get(), get(), get(named(Dispatcher.IO))) }
    workerOf(::SubscriptionWorker)
    single {
        ClashTrees(
            get(),
            get(),
            get(),
            File(androidContext().filesDir, "mihomo"),
            get(named(Scope.APPLICATION)),
            get(named(Dispatcher.IO)),
        )
    }
    single { QuartzRefresh(get(), get(), get(), get(), get(), get(named(Scope.APPLICATION))) }
    single { QuartzDelays(get(), get(), get(), get(), get(named(Scope.APPLICATION))) }
    single { IpLookup(get(named(Scope.APPLICATION))) }
    single { QuartzTunnelOrder(get(), get()) }
    single { app.quartz.core.ui.QuartzRowData(get(), get(), get(named(Scope.APPLICATION))) }

    single<WarpApi> {
        CloudflareWarpApi(
            io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                with(CloudflareWarpApi) { warpTimeouts() }
            }
        )
    }
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
        QuartzSync(
            androidContext(),
            get(named(Scope.APPLICATION)),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
}
