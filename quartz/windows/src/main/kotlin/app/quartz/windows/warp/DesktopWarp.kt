package app.quartz.windows.warp

import app.quartz.warp.CloudflareWarpApi
import app.quartz.warp.EndpointChoice
import app.quartz.warp.GeneratedWarp
import app.quartz.warp.Junk
import app.quartz.warp.WarpApi
import app.quartz.warp.WarpConfigBuilder
import app.quartz.warp.WarpMarkers
import app.quartz.warp.WarpOptions
import app.quartz.warp.WarpVariant
import app.quartz.warp.awg.AwgPayloads
import com.zaneschepke.wireguardautotunnel.client.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.client.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.client.orchestration.TunnelCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The generator's fields as typed (blank or invalid falls back to the default, as on the site). */
data class DesktopWarpForm(
    val variant: WarpVariant = WarpVariant.WG,
    val endpoint: String = "",
    val dns: String = WarpOptions.DEFAULT_DNS,
    val mtu: String = WarpOptions.DEFAULT_MTU.toString(),
    val allowedIps: String = WarpOptions.DEFAULT_ALLOWED_IPS,
    val keepalive: String = "0",
    val deviceType: String = WarpOptions.DEFAULT_DEVICE_TYPE,
    val locale: String = WarpOptions.DEFAULT_LOCALE,
    val jc: String = Junk.DEFAULT.jc.toString(),
    val jMin: String = Junk.DEFAULT.jMin.toString(),
    val jMax: String = Junk.DEFAULT.jMax.toString(),
) {
    fun toOptions() =
        WarpOptions(
            variant = variant,
            junk = Junk(int(jc, 4), int(jMin, 40), int(jMax, 70)),
            endpoint =
                endpoint.trim().takeIf { it.isNotEmpty() }?.let { EndpointChoice.Custom(it) }
                    ?: EndpointChoice.Default,
            mtu = int(mtu, WarpOptions.DEFAULT_MTU),
            dns = dns.trim(),
            allowedIps = allowedIps.trim().ifEmpty { WarpOptions.DEFAULT_ALLOWED_IPS },
            keepalive = keepalive.trim().toIntOrNull()?.takeIf { it > 0 },
            deviceType = deviceType.trim().ifEmpty { WarpOptions.DEFAULT_DEVICE_TYPE },
            locale = locale.trim().ifEmpty { WarpOptions.DEFAULT_LOCALE },
        )

    private fun int(value: String, fallback: Int) =
        value.trim().toIntOrNull()?.takeIf { it >= 0 } ?: fallback
}

/**
 * The WARP generator on Windows: the same shared core as Android (quartz/shared/warp), saving into
 * the desktop app's tunnels. One WARP and one WARP AWG tunnel, found by the config's markers:
 * saving replaces that tunnel's config in place (same name, position, group, rules) and
 * reconnects it if it's running; otherwise a tunnel is added.
 */
class DesktopWarp(
    private val tunnelRepository: TunnelRepository,
    private val tunnelCoordinator: TunnelCoordinator,
    private val api: WarpApi =
        CloudflareWarpApi(
            io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
                with(CloudflareWarpApi) { warpTimeouts() }
            }
        ),
) {
    private val payloads by lazy {
        runCatching {
                DesktopWarp::class.java.getResourceAsStream("/" + AwgPayloads.ASSET)?.use {
                    AwgPayloads.parse(it.reader().readText())
                }
            }
            .getOrNull()
            .orEmpty()
    }

    suspend fun generate(options: WarpOptions): GeneratedWarp =
        withContext(Dispatchers.IO) {
            val registration = api.register(options.deviceType, options.locale)
            val awg = AwgPayloads.pick(payloads, WarpOptions.MIMIC_HOSTS.random())
            GeneratedWarp(registration, awg, WarpConfigBuilder.expiry(registration))
        }

    /** The saved WARP tunnel of [variant], if any. */
    suspend fun existing(variant: WarpVariant): TunnelConfig? =
        tunnelRepository.getAll().firstOrNull { WarpMarkers.parse(it.quickConfig)?.variant == variant }

    suspend fun save(generated: GeneratedWarp, options: WarpOptions): String {
        val config = generated.config(options)
        val current = existing(options.variant)
        if (current != null) {
            tunnelRepository.save(current.copy(quickConfig = config))
            if (current.id in tunnelCoordinator.activeTunnelIds())
                tunnelCoordinator.restartActiveTunnels()
            return current.name
        }
        val names = tunnelRepository.getAll().map { it.name }.toSet()
        val base = WarpConfigBuilder.tunnelName(options.slot, options.variant)
        val name =
            if (base !in names) base
            else generateSequence(1) { it + 1 }.map { "$base ($it)" }.first { it !in names }
        tunnelRepository.save(TunnelConfig(name = name, quickConfig = config))
        return name
    }
}
