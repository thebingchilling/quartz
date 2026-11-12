package app.quartz.engine.mihomo

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A node or group as mihomo reports it. [delay] is the last test in ms, null if none/failed. */
data class MihomoProxy(
    val name: String,
    val type: String,
    val delay: Int?,
    val alive: Boolean,
    /** Groups only: the selected member and all members, in profile order. */
    val now: String? = null,
    val all: List<String> = emptyList(),
    val hidden: Boolean = false,
) {
    val isGroup: Boolean
        get() = all.isNotEmpty()

    /**
     * Selector groups can be switched by the user; url-test/fallback/load-balance pick themselves.
     */
    val isSelectable: Boolean
        get() = type.equals("Selector", ignoreCase = true)
}

data class MihomoProxies(val byName: Map<String, MihomoProxy>, val groups: List<MihomoProxy>)

enum class MihomoMode(val value: String) {
    RULE("rule"),
    GLOBAL("global"),
    DIRECT("direct");

    companion object {
        fun from(value: String?) =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: RULE
    }
}

data class MihomoProvider(
    val name: String,
    val vehicleType: String,
    val updatedAt: String?,
    val proxyCount: Int,
)

class MihomoApiException(message: String) : Exception(message)

/**
 * mihomo's `external-controller` REST API (FORK.md §8). Stable across mihomo releases, so engine
 * updates rarely touch the Clash UI.
 */
class MihomoApi(
    private val controller: MihomoController,
    private val client: HttpClient = sharedClient,
) {

    /** All nodes and groups; groups in profile order (from GLOBAL's member list), GLOBAL last. */
    suspend fun proxies(): MihomoProxies {
        val root =
            get("/proxies")["proxies"] as? JsonObject
                ?: throw MihomoApiException("No proxies in response")
        val byName = root.mapValues { (name, value) -> parseProxy(name, value.jsonObject) }
        val order = byName[GLOBAL]?.all.orEmpty().withIndex().associate { it.value to it.index }
        val groups =
            byName.values
                .filter { it.isGroup }
                .sortedWith(
                    compareBy(
                        { it.name == GLOBAL },
                        { order[it.name] ?: Int.MAX_VALUE },
                        { it.name },
                    )
                )
        return MihomoProxies(byName, groups)
    }

    /** Selects [proxy] in Selector [group]; switching is instant, no reconnect. */
    suspend fun select(group: String, proxy: String) {
        check(
            client.put(url("/proxies/${group.encodeURLPathPart()}")) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("name", proxy) }.toString())
            }
        )
    }

    /** URL-test one node; null on timeout or failure. */
    suspend fun delay(proxy: String, url: String = DEFAULT_TEST_URL, timeoutMs: Int = 5_000): Int? {
        val response =
            client.get(url("/proxies/${proxy.encodeURLPathPart()}/delay")) {
                auth()
                parameter("url", url)
                parameter("timeout", timeoutMs)
            }
        if (!response.status.isSuccess()) return null
        return (json(response)["delay"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    }

    /** URL-test every member of [group] at once; members that fail are missing from the map. */
    suspend fun groupDelay(
        group: String,
        url: String = DEFAULT_TEST_URL,
        timeoutMs: Int = 5_000,
    ): Map<String, Int> {
        val response =
            client.get(url("/group/${group.encodeURLPathPart()}/delay")) {
                auth()
                parameter("url", url)
                parameter("timeout", timeoutMs)
            }
        if (!response.status.isSuccess()) return emptyMap()
        return json(response)
            .mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { name to it }
            }
            .toMap()
    }

    suspend fun mode(): MihomoMode =
        MihomoMode.from((get("/configs")["mode"] as? JsonPrimitive)?.contentOrNull)

    suspend fun setMode(mode: MihomoMode) {
        check(
            client.patch(url("/configs")) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("mode", mode.value) }.toString())
            }
        )
    }

    /** Proxy providers from the profile (the built-in "default" one is left out). */
    suspend fun providers(): List<MihomoProvider> =
        (get("/providers/proxies")["providers"] as? JsonObject).orEmpty().mapNotNull { (name, value)
            ->
            val o = value as? JsonObject ?: return@mapNotNull null
            val vehicle = (o["vehicleType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (name == "default" || vehicle.equals("Compatible", ignoreCase = true))
                return@mapNotNull null
            MihomoProvider(
                name = name,
                vehicleType = vehicle,
                updatedAt = (o["updatedAt"] as? JsonPrimitive)?.contentOrNull,
                proxyCount = (o["proxies"] as? JsonArray)?.size ?: 0,
            )
        }

    suspend fun updateProvider(name: String) {
        check(client.put(url("/providers/proxies/${name.encodeURLPathPart()}")) { auth() })
    }

    suspend fun version(): String =
        (get("/version")["version"] as? JsonPrimitive)?.contentOrNull
            ?: throw MihomoApiException("No version")

    private fun parseProxy(name: String, o: JsonObject): MihomoProxy {
        val history = o["history"] as? JsonArray
        val lastDelay = (history?.lastOrNull() as? JsonObject)?.get("delay") as? JsonPrimitive
        return MihomoProxy(
            name = name,
            type = (o["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            delay = lastDelay?.intOrNull?.takeIf { it > 0 },
            alive = (o["alive"] as? JsonPrimitive)?.booleanOrNull ?: true,
            now = (o["now"] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null },
            all =
                (o["all"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
            hidden = (o["hidden"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private suspend fun get(path: String): JsonObject {
        val response = client.get(url(path)) { auth() }
        check(response)
        return json(response)
    }

    private suspend fun json(response: HttpResponse): JsonObject = runCatching {
        JSON.parseToJsonElement(response.bodyAsText()).jsonObject
    }
        .getOrElse { throw MihomoApiException("Unexpected response from mihomo") }

    private suspend fun check(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val message = runCatching {
                (json(response)["message"] as? JsonPrimitive)?.contentOrNull
            }
                .getOrNull()
            throw MihomoApiException(message ?: "HTTP ${response.status.value}")
        }
    }

    private fun url(path: String) = controller.baseUrl + path

    private fun HttpRequestBuilder.auth() = bearerAuth(controller.secret)

    companion object {
        const val GLOBAL = "GLOBAL"
        const val DEFAULT_TEST_URL = "https://cp.cloudflare.com/generate_204"
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * One client for every MihomoApi: a client holds a connection pool and threads, and one
         * per API object (made on every tree load, delay test and node switch) piled up until the
         * app ran out of memory or threads.
         */
        val sharedClient: HttpClient by lazy { defaultClient() }

        // The controller is on loopback: never route it through a system proxy.
        fun defaultClient() =
            HttpClient(OkHttp) {
                engine { config { proxy(java.net.Proxy.NO_PROXY) } }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 5_000
                }
            }
    }
}
