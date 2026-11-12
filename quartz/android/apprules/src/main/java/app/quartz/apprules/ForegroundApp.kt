package app.quartz.apprules

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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * What tunnel by app needs to know (FORK.md §14).
 *
 * [foreground]: the app on screen. From **app detection** ([QuartzAppWatcherService], an
 * accessibility service) when it's on: instant, and it sees apps in every profile. Otherwise
 * from usage access.
 *
 * [open]: main-profile apps that still have screens alive, i.e. not closed from the recent apps
 * (task switcher). From **usage access**: Android records each screen (activity) resumed, stopped
 * and destroyed; swiping an app away in recents, or backing out of it, destroys its screens. Null
 * when it can't be known: no usage access, or Android 9 and older (no "destroyed" events).
 */
data class AppActivity(val foreground: String?, val open: Set<String>?)

object ForegroundApp {
    private val _watcher = MutableStateFlow<String?>(null)
    private val _watcherConnected = MutableStateFlow(false)

    val watcherConnected: StateFlow<Boolean> = _watcherConnected.asStateFlow()
    private val fromWatcher: StateFlow<String?> = _watcher.asStateFlow()

    internal fun onWatcherConnected(connected: Boolean) {
        _watcherConnected.value = connected
        if (!connected) _watcher.value = null
    }

    internal fun onWatcherApp(packageName: String) {
        _watcher.value = packageName
    }

    fun activity(context: Context): Flow<AppActivity> =
        combine(
                watcherConnected,
                fromWatcher,
                // A failure here must not take auto-tunnel (and the app) down with it.
                usage(context).catch {
                    Timber.e(it, "Usage tracking stopped")
                    emit(AppActivity(null, null))
                },
            ) { connected, watched, usage ->
                AppActivity(
                    foreground = if (connected) watched else usage.foreground,
                    open = usage.open,
                )
            }
            .distinctUntilChanged()

    /**
     * Usage events, read incrementally every couple of seconds while the screen is on (nothing is
     * missed while it's off: the next read starts where the last one ended).
     */
    private fun usage(context: Context): Flow<AppActivity> = flow {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val tracker = OpenApps()
        var since = System.currentTimeMillis() - HISTORY_MS
        var granted: Boolean? = null
        while (true) {
            val nowGranted = hasUsageAccess(context)
            if (nowGranted != granted) {
                // Access just given (or taken): start over from recent history.
                granted = nowGranted
                tracker.clear()
                since = System.currentTimeMillis() - HISTORY_MS
            }
            if (!nowGranted || usage == null) {
                emit(AppActivity(null, null))
            } else if (power?.isInteractive != false) {
                val now = System.currentTimeMillis()
                runCatching {
                        val events = usage.queryEvents(since, now) ?: return@runCatching
                        val event = UsageEvents.Event()
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            tracker.onEvent(event.packageName, event.className, event.eventType)
                        }
                        since = now
                    }
                    .onFailure { Timber.w(it, "Usage events unreadable") }
                emit(
                    AppActivity(
                        tracker.foreground,
                        tracker.open()?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q },
                    )
                )
            }
            delay(POLL)
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            else
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Whether the user turned on Quartz's app detection in Accessibility settings. */
    fun watcherEnabled(context: Context): Boolean {
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        val ours = ComponentName(context, QuartzAppWatcherService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == ours }
    }

    private val POLL = 2.seconds
    /** How far back the first read goes, to know which apps are already open. */
    private const val HISTORY_MS = 6 * 60 * 60 * 1000L
}

/**
 * Which apps have screens alive, from usage events: each screen (package + activity class) is
 * alive from its first resume until it's destroyed. Pure, so it's unit-tested.
 */
class OpenApps {
    private val alive = mutableMapOf<String, MutableSet<String>>()

    var foreground: String? = null
        private set

    /** Whether this phone reports "destroyed" at all; until it has, closing can't be seen. */
    private var seenDestroyed = false

    fun onEvent(packageName: String?, className: String?, type: Int) {
        packageName ?: return
        val screen = className ?: ""
        when (type) {
            RESUMED -> {
                alive.getOrPut(packageName) { mutableSetOf() } += screen
                foreground = packageName
            }
            PAUSED,
            STOPPED -> alive.getOrPut(packageName) { mutableSetOf() } += screen
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

    /** Apps with screens alive; null while no "destroyed" event has been seen (see [seenDestroyed]). */
    fun open(): Set<String>? = if (seenDestroyed) alive.keys.toSet() else null

    fun clear() {
        alive.clear()
        foreground = null
        seenDestroyed = false
    }

    companion object {
        // UsageEvents.Event constants (ACTIVITY_DESTROYED is hidden-public since Android 10).
        const val RESUMED = 1
        const val PAUSED = 2
        const val STOPPED = 23
        const val DESTROYED = 24
    }
}
