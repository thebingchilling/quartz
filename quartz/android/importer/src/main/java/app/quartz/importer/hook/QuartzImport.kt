package app.quartz.importer.hook

import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.Subscriptions
import app.quartz.bridge.Subscriptions.Companion.withInfo
import app.quartz.core.data.EngineProfile
import app.quartz.importer.ClashTemplate
import app.quartz.importer.Detected
import app.quartz.importer.ImportDetector
import app.quartz.importer.ProxyNode
import app.quartz.importer.SubscriptionInfo
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.util.StringValue
import java.net.URI
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Lets upstream's own add-tunnel paths (file, clipboard, QR, link) take any config (FORK.md §7).
 * Upstream hands everything to [claim] / [claimUrl] first: Clash profiles, share links,
 * subscriptions and sing-box configs are saved here as Clash tunnels; OpenVPN is refused with a
 * message for now; WireGuard is handed back to upstream's import unchanged.
 */
object QuartzImport : KoinComponent {
    private val profiles: ClashProfiles by inject()
    private val subscriptions: Subscriptions by inject()
    private val globalEffects: GlobalEffectRepository by inject()

    /**
     * Imports what isn't WireGuard; returns the WireGuard configs (content → name) for upstream.
     */
    suspend fun claim(configs: Map<String, String?>): Map<String, String?> {
        val forUpstream = linkedMapOf<String, String?>()
        for ((content, name) in configs) {
            when (val detected = ImportDetector.detect(content, name)) {
                is Detected.WireGuard,
                is Detected.Unknown -> forUpstream[content] = name // upstream shows its own errors
                is Detected.ProxyList ->
                    if (detected.isSingleWireGuard)
                        forUpstream[wireGuardConfig(detected.proxies[0])] = name
                    else saveProfile(detected, name)
                is Detected.ClashProfile -> saveProfile(detected, name)
                is Detected.SubscriptionUrl -> claimUrl(detected.url)
                is Detected.OpenVpn ->
                    notify(
                        StringValue.StringResource(R.string.quartz_import_openvpn_later),
                        ToastType.Info,
                    )
            }
        }
        return forUpstream
    }

    /**
     * A link from upstream's "add from URL": fetched with a Clash User-Agent. A Clash profile or
     * proxy list becomes an auto-updating Clash tunnel (true); anything else goes back to
     * upstream's own download (false).
     */
    suspend fun claimUrl(url: String): Boolean {
        val fetched = runCatching {
            subscriptions.fetch(url)
        }
            .getOrElse {
                return false
            }
        val detected = fetched.detected
        if (
            detected !is Detected.ClashProfile &&
                (detected !is Detected.ProxyList || detected.isSingleWireGuard)
        )
            return false
        val name =
            fetched.info.name
                ?: runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
                ?: "Clash"
        val subscription =
            EngineProfile(
                    tunnelId = 0,
                    engine = EngineProfile.ENGINE_MIHOMO,
                    profileFile = "",
                    subscriptionUrl = url,
                    userAgent = SubscriptionInfo.USER_AGENT,
                    template = Subscriptions.templateKey(ClashTemplate.Simple),
                    lastUpdatedAt = System.currentTimeMillis(),
                )
                .withInfo(fetched.info)
        saveProfile(detected, name, subscription)
        return true
    }

    private suspend fun saveProfile(
        detected: Detected,
        name: String?,
        subscription: EngineProfile? = null,
    ) {
        runCatching {
            val yaml = Subscriptions.profileYaml(detected, ClashTemplate.Simple)
            val title = name?.substringBeforeLast('.')?.ifBlank { null } ?: defaultName(detected)
            profiles.create(title, yaml, subscription = subscription)
            notify(
                StringValue.StringResource(R.string.quartz_import_saved, title),
                ToastType.Success,
            )
        }
            .onFailure {
                Timber.e(it, "Clash import failed")
                notify(StringValue.DynamicString(it.message ?: "Import failed"), ToastType.Error)
            }
    }

    private suspend fun notify(message: StringValue, type: ToastType) =
        globalEffects.post(GlobalSideEffect.Snackbar(message, type))

    private fun defaultName(detected: Detected) =
        (detected as? Detected.ProxyList)?.proxies?.singleOrNull()?.get("name")?.toString()
            ?: "Clash"

    /** A wireguard:// share link as a WireGuard config, for upstream's import. */
    fun wireGuardConfig(node: ProxyNode): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${node["private-key"]}")
        val addresses = listOfNotNull(node["ip"]?.let { "$it/32" }, node["ipv6"]?.let { "$it/128" })
        if (addresses.isNotEmpty()) appendLine("Address = ${addresses.joinToString(", ")}")
        node["mtu"]?.let { appendLine("MTU = $it") }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${node["public-key"]}")
        node["pre-shared-key"]?.let { appendLine("PresharedKey = $it") }
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        val server = node["server"].toString()
        appendLine("Endpoint = ${if (':' in server) "[$server]" else server}:${node["port"]}")
    }
}
