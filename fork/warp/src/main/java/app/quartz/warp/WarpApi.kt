package app.quartz.warp

import com.wgtunnel.parser.crypto.Key
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WarpApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Cloudflare's WARP client API. Unofficial, so it sits behind this small interface. */
interface WarpApi {
    suspend fun register(license: String?): WarpRegistration

    suspend fun delete(id: String, token: String, apiVersion: String)
}

class CloudflareWarpApi(
    private val client: HttpClient = defaultClient(),
    private val baseUrl: String = "https://api.cloudflareclient.com",
) : WarpApi {

    override suspend fun register(license: String?): WarpRegistration {
        val privateKey = Key.generatePrivateKey()
        val publicKey = Key.generatePublicKey(privateKey).toBase64()
        var lastError: Throwable? = null
        for (version in API_VERSIONS) {
            val result = runCatching { registerWith(version, privateKey.toBase64(), publicKey) }
            result.onSuccess { registration ->
                license?.trim()?.takeIf { it.isNotEmpty() }?.let { applyLicense(registration, it) }
                return registration
            }
            lastError = result.exceptionOrNull()
            if (lastError is WarpApiException && lastError.message?.startsWith("License") == true)
                throw lastError
        }
        throw WarpApiException("WARP registration failed: ${lastError?.message}", lastError)
    }

    override suspend fun delete(id: String, token: String, apiVersion: String) {
        val response =
            client.delete("$baseUrl/$apiVersion/reg/$id") {
                headers()
                bearerAuth(token)
            }
        // 404/401: already gone or revoked, nothing left to delete.
        if (!response.status.isSuccess() && response.status.value !in setOf(401, 403, 404)) {
            throw WarpApiException("Delete failed: HTTP ${response.status.value}")
        }
    }

    private suspend fun registerWith(
        version: String,
        privateKey: String,
        publicKey: String,
    ): WarpRegistration {
        val installId = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("key", publicKey)
            put("install_id", installId)
            put("fcm_token", "$installId:APA91b${UUID.randomUUID().toString().replace("-", "")}")
            put("tos", TOS_FORMAT.format(Instant.now()))
            put("model", "PC")
            put("serial_number", installId)
            put("type", "Android")
            put("locale", "en_US")
        }
        val response =
            client.post("$baseUrl/$version/reg") {
                headers()
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        val json = response.json()
        return parseRegistration(json, version, privateKey)
    }

    private suspend fun applyLicense(registration: WarpRegistration, license: String) {
        val response =
            client.put("$baseUrl/${registration.apiVersion}/reg/${registration.id}/account") {
                headers()
                bearerAuth(registration.token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("license", license) }.toString())
            }
        if (!response.status.isSuccess()) {
            runCatching { delete(registration.id, registration.token, registration.apiVersion) }
            throw WarpApiException(
                "License not accepted: HTTP ${response.status.value} ${response.bodyAsText().take(200)}"
            )
        }
    }

    private suspend fun HttpResponse.json(): JsonObject {
        val text = bodyAsText()
        if (!status.isSuccess()) throw WarpApiException("HTTP ${status.value}: ${text.take(200)}")
        return runCatching { JSON.parseToJsonElement(text).jsonObject }
            .getOrElse { throw WarpApiException("Unexpected response", it) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.headers() {
        header(HttpHeaders.UserAgent, "okhttp/3.12.1")
        header("CF-Client-Version", CLIENT_VERSION)
    }

    companion object {
        /** Tried in order until one registers (FORK.md §9). */
        val API_VERSIONS = listOf("v0a737", "v0a1909051800", "v0i1909051800")
        private const val CLIENT_VERSION = "a-6.30-3596"
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private val TOS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                .withZone(java.time.ZoneOffset.UTC)

        fun defaultClient() =
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                }
            }

        /** Accepts both the bare and the `{"result": …}`-wrapped response shapes. */
        fun parseRegistration(
            response: JsonObject,
            version: String,
            privateKey: String,
        ): WarpRegistration {
            val root = (response["result"] as? JsonObject) ?: response
            fun JsonElement?.str(): String? =
                (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            fun JsonObject.obj(name: String) = this[name] as? JsonObject
            val config = root.obj("config") ?: throw WarpApiException("Response has no config")
            val peer =
                config["peers"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw WarpApiException("Response has no peer")
            val addresses =
                config.obj("interface")?.obj("addresses")
                    ?: throw WarpApiException("Response has no addresses")
            val account = root.obj("account")
            val endpoint = peer.obj("endpoint")
            val host =
                endpoint?.get("host").str()?.takeIf { it.isNotBlank() }
                    ?: WarpEndpoints.DEFAULT_HOST
            return WarpRegistration(
                id = root["id"].str() ?: throw WarpApiException("Response has no id"),
                token = root["token"].str() ?: throw WarpApiException("Response has no token"),
                apiVersion = version,
                privateKey = privateKey,
                peerPublicKey =
                    peer["public_key"]?.jsonPrimitive?.content
                        ?: throw WarpApiException("Response has no peer key"),
                endpointHost = host,
                addressV4 =
                    addresses["v4"].str() ?: throw WarpApiException("Response has no IPv4 address"),
                addressV6 = addresses["v6"].str(),
                accountType = account?.get("account_type").str(),
                // Unset TTL comes back as 0001-01-01T00:00:00Z: only a future time counts.
                ttlAt =
                    account
                        ?.get("ttl")
                        .str()
                        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?.takeIf { it > System.currentTimeMillis() },
            )
        }
    }
}
