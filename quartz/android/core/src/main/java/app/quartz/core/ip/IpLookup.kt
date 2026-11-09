package app.quartz.core.ip

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Where the connection exits, as an IP lookup service sees it. */
data class IpInfo(
    val ip: String,
    val countryCode: String? = null,
    val country: String? = null,
    val region: String? = null,
    val city: String? = null,
    val isp: String? = null,
) {
    /** The country's flag emoji, from its two-letter code. */
    val flag: String?
        get() =
            countryCode
                ?.uppercase()
                ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
                ?.let { code ->
                    code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
                }

    /** "City, Region, Country", without repeats or blanks. */
    val place: String
        get() =
            listOfNotNull(city, region, country)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
}

sealed interface IpLookupState {
    data object Loading : IpLookupState

    data class Found(val info: IpInfo) : IpLookupState

    data object Failed : IpLookupState
}

/**
 * Looks up the public IP, location and ISP of a connected tunnel (FORK.md §6): the app's own
 * requests go through the tunnel, so the lookup service sees the tunnel's exit. ipwho.is first,
 * ipapi.co as a fallback; both are HTTPS and need no key. One lookup per connection, cached by
 * its key (tunnel, connect time, and [invalidate] for a Clash node switch).
 */
class IpLookup(private val scope: CoroutineScope) {
    private val _results = MutableStateFlow<Map<String, IpLookupState>>(emptyMap())
    val results: StateFlow<Map<String, IpLookupState>> = _results.asStateFlow()

    private val _generation = MutableStateFlow(0)
    /** Bumped when the exit may have changed without a reconnect (a Clash node switch). */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 8_000
            }
        }
    }

    fun invalidate() = _generation.update { it + 1 }

    /** Starts a lookup for [key] unless one is cached or running ([retry] redoes a failed one). */
    fun ensure(key: String, retry: Boolean = false) {
        val current = _results.value[key]
        if (current is IpLookupState.Loading || current is IpLookupState.Found) return
        if (current is IpLookupState.Failed && !retry) return
        _results.update { it + (key to IpLookupState.Loading) }
        scope.launch {
            // Right after connecting the tunnel may need a moment before traffic flows.
            var info: IpInfo? = null
            for (attempt in 0 until ATTEMPTS) {
                info = runCatching { lookup() }.getOrNull()
                if (info != null) break
                delay(RETRY_DELAY)
            }
            val result = info?.let { IpLookupState.Found(it) } ?: IpLookupState.Failed
            _results.update { it + (key to result) }
        }
    }

    private suspend fun lookup(): IpInfo? =
        runCatching { ipWhoIs() }.getOrNull() ?: runCatching { ipApiCo() }.getOrNull()

    private suspend fun ipWhoIs(): IpInfo? {
        val o = fetch("https://ipwho.is/") ?: return null
        if (o.bool("success") == false) return null
        val connection = o["connection"] as? JsonObject
        return IpInfo(
            ip = o.str("ip") ?: return null,
            countryCode = o.str("country_code"),
            country = o.str("country"),
            region = o.str("region"),
            city = o.str("city"),
            isp = connection?.str("isp") ?: connection?.str("org"),
        )
    }

    private suspend fun ipApiCo(): IpInfo? {
        val o = fetch("https://ipapi.co/json/") ?: return null
        if (o.bool("error") == true) return null
        return IpInfo(
            ip = o.str("ip") ?: return null,
            countryCode = o.str("country_code"),
            country = o.str("country_name"),
            region = o.str("region"),
            city = o.str("city"),
            isp = o.str("org"),
        )
    }

    private suspend fun fetch(url: String): JsonObject? {
        val response = client.get(url) { header("Accept", "application/json") }
        if (!response.status.isSuccess()) return null
        return JSON.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.str(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val ATTEMPTS = 4
        val RETRY_DELAY = 3.seconds
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
