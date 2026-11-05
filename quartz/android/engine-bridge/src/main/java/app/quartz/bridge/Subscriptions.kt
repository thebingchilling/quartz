package app.quartz.bridge

import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import app.quartz.engine.mihomo.MihomoEngine
import app.quartz.importer.ClashTemplate
import app.quartz.importer.Detected
import app.quartz.importer.ImportDetector
import app.quartz.importer.SubscriptionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class SubscriptionException(message: String) : Exception(message)

/** A downloaded subscription: what it contains and what its headers said. */
data class Fetched(val detected: Detected, val info: SubscriptionInfo)

/**
 * Subscriptions (FORK.md §7): download with a mihomo User-Agent, read the provider's headers, turn
 * the body into a profile (full profiles as-is, proxy lists through the chosen template), and keep
 * the last good profile when an update fails.
 */
class Subscriptions(
    private val profiles: ClashProfiles,
    private val profileDao: EngineProfileDao,
    private val engine: MihomoEngine,
    private val ioDispatcher: CoroutineDispatcher,
    private val client: HttpClient = defaultClient(),
) {
    suspend fun fetch(url: String, userAgent: String = SubscriptionInfo.USER_AGENT): Fetched =
        withContext(ioDispatcher) {
            val response = runCatching {
                client.get(url) { header(HttpHeaders.UserAgent, userAgent) }
            }
                .getOrElse {
                    throw SubscriptionException("Couldn't reach the subscription: ${it.message}")
                }
            if (!response.status.isSuccess())
                throw SubscriptionException("Subscription answered HTTP ${response.status.value}")
            val headers =
                response.headers.entries().associate { it.key to it.value.joinToString(",") }
            val body = response.bodyAsText()
            Fetched(ImportDetector.detect(body), SubscriptionInfo.fromHeaders(headers))
        }

    /**
     * Re-downloads [tunnelId]'s subscription and replaces its profile; the old one stays on
     * failure.
     */
    suspend fun update(tunnelId: Int): Result<Unit> =
        withContext(ioDispatcher) {
            val profile =
                profileDao.get(tunnelId)
                    ?: return@withContext Result.failure(
                        SubscriptionException("Not a Clash tunnel")
                    )
            val url =
                profile.subscriptionUrl
                    ?: return@withContext Result.failure(SubscriptionException("No subscription"))
            runCatching {
                val fetched = fetch(url, profile.userAgent ?: SubscriptionInfo.USER_AGENT)
                val yaml = profileYaml(fetched.detected, templateOf(profile))
                validate(yaml)?.let { throw SubscriptionException(it) }
                profiles.writeYaml(tunnelId, yaml)
                profileDao.upsert(
                    profile
                        .withInfo(fetched.info)
                        .copy(lastUpdatedAt = System.currentTimeMillis(), lastError = null)
                )
            }
                .onFailure { profileDao.upsert(profile.copy(lastError = it.message)) }
        }

    /** mihomo's own check of a profile, without running it; null when it's fine. */
    suspend fun validate(yaml: String): String? =
        withContext(ioDispatcher) {
            val temp =
                File.createTempFile("profile", ".yaml", profiles.directory.apply { mkdirs() })
            try {
                temp.writeText(yaml)
                runCatching { engine.validate(temp) }.getOrElse { it.message }
            } finally {
                temp.delete()
            }
        }

    companion object {
        fun defaultClient() =
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 15_000
                }
            }

        /** Profile text for anything Clash can run, or an error for what it can't. */
        fun profileYaml(detected: Detected, template: ClashTemplate): String =
            when (detected) {
                is Detected.ClashProfile -> detected.yaml
                is Detected.ProxyList -> {
                    if (detected.proxies.isEmpty() && detected.providers.isEmpty()) {
                        throw SubscriptionException("No usable proxies in it")
                    }
                    ClashTemplate.build(template, detected.proxies, detected.providers)
                }
                is Detected.WireGuard ->
                    throw SubscriptionException("It's a WireGuard config, not a Clash profile")
                is Detected.OpenVpn ->
                    throw SubscriptionException("OpenVPN configs aren't supported yet")
                is Detected.SubscriptionUrl ->
                    throw SubscriptionException("It only points to another link")
                is Detected.Unknown -> throw SubscriptionException(detected.reason)
            }

        fun templateKey(template: ClashTemplate): String =
            when (template) {
                ClashTemplate.Simple -> "simple"
                is ClashTemplate.RuleBased -> "rule:${template.countryCode}"
                is ClashTemplate.Custom -> "custom"
            }

        fun templateOf(profile: EngineProfile): ClashTemplate {
            val key = profile.template ?: return ClashTemplate.Simple
            return when {
                key.startsWith("rule:") -> ClashTemplate.RuleBased(key.removePrefix("rule:"))
                key == "custom" && profile.customTemplate != null ->
                    ClashTemplate.Custom(profile.customTemplate)
                else -> ClashTemplate.Simple
            }
        }

        fun defaultCountry(): String = Locale.getDefault().country.ifEmpty { "US" }

        fun EngineProfile.withInfo(info: SubscriptionInfo) =
            copy(
                usedBytes = info.usedBytes,
                totalBytes = info.totalBytes,
                subscriptionExpire = info.expireAt,
                webPageUrl = info.webPageUrl ?: webPageUrl,
                updateIntervalHours = updateIntervalHours ?: info.updateIntervalHours,
            )
    }
}
