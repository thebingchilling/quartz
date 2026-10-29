package com.zaneschepke.wireguardautotunnel.wifi

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import com.wgtunnel.backend.loadBackendNativeLibrary
import com.zaneschepke.wireguardautotunnel.core.crypto.HmacProtector
import com.zaneschepke.wireguardautotunnel.core.helper.FilePathsHelper
import com.zaneschepke.wireguardautotunnel.core.ipc.Headers
import com.zaneschepke.wireguardautotunnel.core.ipc.IPC
import com.zaneschepke.wireguardautotunnel.core.ipc.Routes
import com.zaneschepke.wireguardautotunnel.core.ipc.dto.request.WifiSsidReport
import com.zaneschepke.wireguardautotunnel.core.profile.AppVariant
import com.zaneschepke.wireguardautotunnel.core.wifi.WifiAuthorization
import com.zaneschepke.wireguardautotunnel.core.wifi.WifiReadStep
import com.zaneschepke.wireguardautotunnel.core.wifi.WifiSsidNative
import com.zaneschepke.wireguardautotunnel.core.wifi.wifiReadStep
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.unixSocket
import io.ktor.client.utils.EmptyContent
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val log = Logger.withTag("WifiAgent")

private const val AUTH_RECHECK_MS = 2_000L
private const val RETRY_SLICE_MS = 350L
private const val RETRY_WINDOW_MS = 5_000L
private const val LIBRARY_RETRY_MS = 30_000L
private const val POST_BACKOFF_MAX_MS = 30_000L

internal enum class Wake {
    Connected,
    Underlay,
    Authorization,
}

/**
 * User-session reader for the macOS Wi-Fi name.
 */
fun main() {
    Logger.setLogWriters(CommonWriter())
    AppVariant.applyProcessDefaults()
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
        exitProcess(0)
    }

    val client = daemonClient()
    val wakes = LinkedBlockingQueue<Wake>()
    Thread({ watchUnderlay(client, wakes) }, "wifi-underlay").apply {
        isDaemon = true
        start()
    }

    var libraryReady = false
    var needsAuth = true
    var nextAuthCheckAt = 0L
    var retryUntil = 0L
    var postDelayMs = 0L
    var lastReport: String? = null
    var notedWithheld = false
    var notedUnauthorized = false
    var askedForAuthorization = false
    var postedAuthorization: Int? = null

    fun postAuthorizationIfChanged() {
        val code = WifiSsidNative.authorizationStatus().code
        if (postedAuthorization == code) return
        // Leave the current name alone. An empty name is a separate report.
        postWifiReport(client, ssid = null, authorization = code)
        postedAuthorization = code
    }

    while (true) {
        if (!libraryReady) {
            libraryReady = runCatching { loadBackendNativeLibrary() }.getOrDefault(false)
            if (!libraryReady) {
                log.e { "Wi-Fi name library failed to load" }
                Thread.sleep(LIBRARY_RETRY_MS)
                continue
            }
        }

        try {
            val wake = awaitWifiWake(wakes, needsAuth, nextAuthCheckAt, retryUntil, postDelayMs)
            if (wake != null) {
                retryUntil = System.currentTimeMillis() + RETRY_WINDOW_MS
                postDelayMs = 0L
                notedWithheld = false
            }
            val deadlineElapsed = retryUntil != 0L && System.currentTimeMillis() >= retryUntil

            when (val step = currentWifiReadStep(deadlineElapsed)) {
                WifiReadStep.RecheckAuth -> {
                    needsAuth = true
                    nextAuthCheckAt = System.currentTimeMillis() + AUTH_RECHECK_MS
                    retryUntil = 0L
                    postDelayMs = 0L
                    val status = WifiSsidNative.authorizationStatus()
                    // One request per process so the helper appears in Location Services.
                    // macOS does not present the panel for this agent. The app opens Settings.
                    if (!askedForAuthorization && status == WifiAuthorization.NotDetermined) {
                        askedForAuthorization = true
                        log.i { "Requesting Wi-Fi name permission" }
                        runCatching { WifiSsidNative.requestAuthorization() }
                            .onFailure { log.w(it) { "Wi-Fi name prompt failed" } }
                    }
                    if (postedAuthorization != status.code) {
                        postWifiReport(client, ssid = "", authorization = status.code)
                        postedAuthorization = status.code
                    }
                    if (!notedUnauthorized) {
                        notedUnauthorized = true
                        log.i { "Wi-Fi name is not authorized (${status.code})" }
                    }
                }
                WifiReadStep.Wait -> {
                    needsAuth = false
                    nextAuthCheckAt = 0L
                    notedUnauthorized = false
                    postAuthorizationIfChanged()
                    if (retryUntil == 0L) {
                        retryUntil = System.currentTimeMillis() + RETRY_WINDOW_MS
                    }
                }
                WifiReadStep.Stop -> {
                    needsAuth = false
                    nextAuthCheckAt = 0L
                    notedUnauthorized = false
                    retryUntil = 0L
                    postDelayMs = 0L
                    postAuthorizationIfChanged()
                    if (!notedWithheld) {
                        notedWithheld = true
                        log.i { "Wi-Fi name withheld" }
                    }
                }
                is WifiReadStep.Post -> {
                    needsAuth = false
                    nextAuthCheckAt = 0L
                    notedUnauthorized = false
                    notedWithheld = false
                    retryUntil = 0L
                    val report = step.ssid
                    val authorization = WifiSsidNative.authorizationStatus().code
                    postWifiReport(client, ssid = report, authorization = authorization)
                    postedAuthorization = authorization
                    postDelayMs = 0L
                    val announce =
                        lastReport != report || wake == Wake.Connected || wake == Wake.Authorization
                    lastReport = report
                    if (announce) {
                        log.i {
                            if (report.isEmpty()) "Cleared Wi-Fi name" else "Posted Wi-Fi name"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.w(e) { "Wi-Fi name update failed" }
            lastReport = null
            retryUntil = 0L
            postDelayMs =
                (if (postDelayMs == 0L) 500L else postDelayMs * 2).coerceAtMost(POST_BACKOFF_MAX_MS)
            if (needsAuth) nextAuthCheckAt = System.currentTimeMillis() + postDelayMs
        }
    }
}

private fun postWifiReport(client: HttpClient, ssid: String?, authorization: Int) {
    runBlocking {
        client.post(Routes.DAEMON_WIFI_SSID) {
            setBody(WifiSsidReport(ssid = ssid, authorization = authorization))
        }
    }
}

private fun currentWifiReadStep(deadlineElapsed: Boolean): WifiReadStep {
    if (!WifiSsidNative.authorizationStatus().granted) return WifiReadStep.RecheckAuth
    val associated = WifiSsidNative.associated()
    val ssid = if (associated) WifiSsidNative.currentSsid() else ""
    return wifiReadStep(
        granted = true,
        associated = associated,
        ssid = ssid,
        deadlineElapsed = deadlineElapsed,
    )
}

private fun awaitWifiWake(
    wakes: BlockingQueue<Wake>,
    needsAuth: Boolean,
    nextAuthCheckAt: Long,
    retryUntil: Long,
    postDelayMs: Long,
): Wake? {
    if (needsAuth) {
        val waitMs =
            if (nextAuthCheckAt == 0L) {
                0L
            } else {
                (nextAuthCheckAt - System.currentTimeMillis()).coerceAtLeast(0)
            }
        // Underlay events are discarded until Location is granted. A grant or a fresh daemon
        // connection still wakes a read.
        return park(wakes, waitMs, acceptUnderlay = false)
    }
    val now = System.currentTimeMillis()
    val timeoutMs =
        when {
            retryUntil != 0L -> {
                val remaining = retryUntil - now
                if (remaining < RETRY_SLICE_MS) remaining.coerceAtLeast(1) else RETRY_SLICE_MS
            }
            postDelayMs != 0L -> postDelayMs
            else -> null
        }
    return park(wakes, timeoutMs, acceptUnderlay = true)
}

private fun park(wakes: BlockingQueue<Wake>, timeoutMs: Long?, acceptUnderlay: Boolean): Wake? {
    val queued = drainWifiWakes(wakes)
    combineWifiWake(queued, authChanged = false, acceptUnderlay = acceptUnderlay)?.let {
        return it
    }
    val seconds = if (timeoutMs == null) -1.0 else timeoutMs.coerceAtLeast(0) / 1000.0
    val authChanged =
        try {
            WifiSsidNative.waitForEvents(seconds)
        } catch (e: UnsatisfiedLinkError) {
            log.e(e) { "Wi-Fi wait is missing from the native library" }
            Thread.sleep(LIBRARY_RETRY_MS)
            false
        }
    return combineWifiWake(
        drainWifiWakes(wakes),
        authChanged = authChanged,
        acceptUnderlay = acceptUnderlay,
    )
}

private fun drainWifiWakes(wakes: BlockingQueue<Wake>): List<Wake> {
    val drained = ArrayList<Wake>()
    while (true) {
        drained.add(wakes.poll() ?: return drained)
    }
}

internal fun combineWifiWake(
    queued: List<Wake>,
    authChanged: Boolean,
    acceptUnderlay: Boolean,
): Wake? {
    var connected = false
    var underlay = false
    for (wake in queued) {
        if (wake == Wake.Connected) connected = true
        if (wake == Wake.Underlay) underlay = true
    }
    if (connected) return Wake.Connected
    if (authChanged) return Wake.Authorization
    if (acceptUnderlay && underlay) return Wake.Underlay
    return null
}

private fun watchUnderlay(client: HttpClient, wakes: BlockingQueue<Wake>) {
    var failures = 0
    while (!Thread.currentThread().isInterrupted) {
        var connected = false
        try {
            runBlocking {
                client.webSocket(Routes.DAEMON_WIFI_UNDERLAY_WS) {
                    connected = true
                    wakes.put(Wake.Connected)
                    runCatching { WifiSsidNative.wakeWait() }
                    for (frame in incoming) {
                        if (frame is Frame.Text && frame.readText() == Routes.WIFI_UNDERLAY_READ) {
                            wakes.put(Wake.Underlay)
                            runCatching { WifiSsidNative.wakeWait() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (failures == 0) log.w(e) { "Wi-Fi underlay stream failed" }
        }
        failures = if (connected) 0 else failures + 1
        val shift = (failures - 1).coerceAtLeast(0).coerceAtMost(4)
        try {
            Thread.sleep((200L shl shift).coerceAtMost(3_000L))
        } catch (_: InterruptedException) {
            return
        }
    }
}

private fun daemonClient(): HttpClient {
    val json = Json { encodeDefaults = true }
    return HttpClient(CIO) {
        defaultRequest { contentType(ContentType.Application.Json) }
        install(ContentNegotiation) { json(json) }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = Long.MAX_VALUE
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 5_000
        }
        install("UnixSocket") {
            requestPipeline.intercept(HttpRequestPipeline.Before) {
                context.unixSocket(FilePathsHelper.getDaemonSocketPath())
                proceedWith(subject)
            }
        }
        install("HmacSigner") {
            requestPipeline.intercept(HttpRequestPipeline.Render) { payload ->
                val secret = IPC.getIPCSecret()
                val timestamp = System.currentTimeMillis() / 1000
                val bodyString =
                    when (payload) {
                        is TextContent -> payload.text
                        is EmptyContent -> ""
                        else -> ""
                    }
                val signature = HmacProtector.generateSignature(secret, timestamp, bodyString)
                context.header(Headers.HMAC_TIMESTAMP, timestamp.toString())
                context.header(Headers.HMAC_SIGNATURE, signature)
                context.header(Headers.HMAC_KEY_PATH, IPC.getIpcKeyPath())
                proceedWith(payload)
            }
        }
        expectSuccess = true
    }
}
