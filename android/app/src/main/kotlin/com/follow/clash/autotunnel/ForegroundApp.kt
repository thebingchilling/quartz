package com.follow.clash.autotunnel

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * [foreground] comes from [AppWatcherService] when it's on (it also sees work-profile and Secure
 * Folder apps), else from usage access. [open] is the main-profile apps not yet closed from the
 * recent apps, null when that can't be known (no usage access, or Android 9 and older).
 */
data class AppActivity(val foreground: String?, val open: Set<String>?)

object ForegroundApp {
    private val watched = MutableStateFlow<String?>(null)
    private val watcherConnected = MutableStateFlow(false)

    internal fun onWatcherConnected(connected: Boolean) {
        watcherConnected.value = connected
        if (!connected) watched.value = null
    }

    internal fun onWatcherApp(packageName: String) {
        watched.value = packageName
    }

    fun activity(context: Context): Flow<AppActivity> =
        combine(watcherConnected, watched, usage(context)) { connected, app, usage ->
            AppActivity(if (connected) app else usage.foreground, usage.open)
        }.distinctUntilChanged()

    private fun usage(context: Context): Flow<AppActivity> = flow {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val tracker = OpenApps()
        var since = System.currentTimeMillis() - HISTORY_MS
        var granted: Boolean? = null
        while (true) {
            val nowGranted = hasUsageAccess(context)
            if (nowGranted != granted) {
                granted = nowGranted
                tracker.clear()
                since = System.currentTimeMillis() - HISTORY_MS
            }
            if (!nowGranted || usage == null) {
                emit(AppActivity(null, null))
            } else if (power?.isInteractive != false) {
                val now = System.currentTimeMillis()
                runCatching { usage.queryEvents(since, now) }.getOrNull()?.let { events ->
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        tracker.onEvent(event.packageName, event.className, event.eventType)
                    }
                    since = now
                }
                emit(
                    AppActivity(
                        tracker.foreground,
                        tracker.open()?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q },
                    )
                )
            }
            delay(POLL_MS)
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun watcherEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val ours = ComponentName(context, AppWatcherService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == ours }
    }

    private const val POLL_MS = 2_000L
    private const val HISTORY_MS = 6 * 60 * 60 * 1000L
}

/** Each screen (package + activity) is alive from its first resume until it's destroyed. */
class OpenApps {
    private val alive = mutableMapOf<String, MutableSet<String>>()

    var foreground: String? = null
        private set

    private var seenDestroyed = false

    fun onEvent(packageName: String?, className: String?, type: Int) {
        packageName ?: return
        val screen = className ?: ""
        when (type) {
            RESUMED -> {
                alive.getOrPut(packageName) { mutableSetOf() } += screen
                foreground = packageName
            }
            PAUSED, STOPPED -> alive.getOrPut(packageName) { mutableSetOf() } += screen
            DESTROYED -> {
                seenDestroyed = true
                alive[packageName]?.let {
                    it -= screen
                    if (it.isEmpty()) alive -= packageName
                }
                if (foreground == packageName && packageName !in alive) foreground = null
            }
        }
    }

    // Until a phone has reported one "destroyed" event, closing an app can't be seen at all.
    fun open(): Set<String>? = if (seenDestroyed) alive.keys.toSet() else null

    fun clear() {
        alive.clear()
        foreground = null
        seenDestroyed = false
    }

    companion object {
        const val RESUMED = 1
        const val PAUSED = 2
        const val STOPPED = 23
        const val DESTROYED = 24
    }
}
