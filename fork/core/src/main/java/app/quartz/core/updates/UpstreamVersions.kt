package app.quartz.core.updates

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One upstream this build is made from, and what's newest there. */
data class UpstreamVersion(
    val name: String,
    /**
     * What this build is based on; empty when it isn't included yet (e.g. OpenVPN before step 7).
     */
    val current: String,
    val latest: String?,
    val error: String? = null,
) {
    val included: Boolean
        get() = current.isNotEmpty()

    val updateAvailable: Boolean
        get() = included && latest != null && VersionCompare.compare(latest, current) > 0
}

/**
 * Compares the bases baked into this build with the latest WG Tunnel Android/Desktop, mihomo and
 * openvpn3 releases. Updating is a manual `quartz-release` run with "update" (FORK.md §2).
 */
class UpstreamVersions(private val client: HttpClient = defaultClient()) {

    data class Bases(
        val wgTunnel: String,
        val wgTunnelDesktop: String,
        val mihomo: String,
        val openvpn3: String,
    )

    suspend fun check(bases: Bases): List<UpstreamVersion> = coroutineScope {
        listOf(
                async { latestRelease("WG Tunnel", "wgtunnel/android", bases.wgTunnel) },
                async {
                    latestRelease("WG Tunnel Desktop", "wgtunnel/desktop", bases.wgTunnelDesktop)
                },
                async { latestRelease("mihomo", "MetaCubeX/mihomo", bases.mihomo) },
                async { latestTag("OpenVPN 3", "OpenVPN/openvpn3", "release/", bases.openvpn3) },
            )
            .awaitAll()
    }

    /** `releases/latest` skips pre-releases (mihomo's alpha builds). */
    private suspend fun latestRelease(name: String, repo: String, current: String) = runCatching {
        val root = getJson("$API/repos/$repo/releases/latest") as JsonObject
        UpstreamVersion(name, current, (root["tag_name"] as? JsonPrimitive)?.contentOrNull)
    }
        .getOrElse { UpstreamVersion(name, current, null, it.message) }

    /** openvpn3 tags releases as `release/x.y.z` without GitHub releases. */
    private suspend fun latestTag(name: String, repo: String, prefix: String, current: String) =
        runCatching {
            val tags = getJson("$API/repos/$repo/tags?per_page=100") as JsonArray
            val latest =
                tags
                    .mapNotNull { (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull }
                    .filter { it.startsWith(prefix) }
                    .maxWithOrNull { a, b -> VersionCompare.compare(a, b) }
            UpstreamVersion(name, current, latest)
        }
        .getOrElse { UpstreamVersion(name, current, null, it.message) }

    private suspend fun getJson(url: String) =
        client
            .get(url) { header("Accept", "application/vnd.github+json") }
            .let { response ->
                if (!response.status.isSuccess()) error("GitHub: HTTP ${response.status.value}")
                JSON.parseToJsonElement(response.bodyAsText())
            }

    companion object {
        private const val API = "https://api.github.com"
        const val UPDATE_WORKFLOW_URL =
            "https://github.com/thebingchilling/quartz/actions/workflows/quartz-release.yml"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun defaultClient() =
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 20_000
                    connectTimeoutMillis = 10_000
                }
            }
    }
}

/** Compares version strings by their numbers: v1.19.33, 5.8.3, release/3.11.8. */
object VersionCompare {
    fun numbers(version: String): List<Int> =
        Regex("\\d+")
            .findAll(version.substringAfterLast('/'))
            .map { it.value.toIntOrNull() ?: 0 }
            .toList()

    fun compare(a: String, b: String): Int {
        val x = numbers(a)
        val y = numbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
