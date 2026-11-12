package app.quartz.apprules

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * The app on screen, for auto-tunnel's app rules (FORK.md §14). Two sources:
 * - **App detection** ([QuartzAppWatcherService], an accessibility service): instant, and sees apps
 *   in every profile (work profile, private space, Secure Folder) since they share the screen.
 * - **Usage access** (UsageStatsManager), when app detection is off: polled every couple of
 *   seconds while the screen is on, and sees only the main profile's apps.
 */
object ForegroundApp {
    private val _watcher = MutableStateFlow<String?>(null)
    private val _watcherConnected = MutableStateFlow(false)

    /** Package reported by the accessibility service, while it's connected. */
    val fromWatcher: StateFlow<String?> = _watcher.asStateFlow()
    val watcherConnected: StateFlow<Boolean> = _watcherConnected.asStateFlow()

    internal fun onWatcherConnected(connected: Boolean) {
        _watcherConnected.value = connected
        if (!connected) _watcher.value = null
    }

    internal fun onWatcherApp(packageName: String) {
        _watcher.value = packageName
    }

    /** The app on screen: from app detection when it's on, else polled through usage access. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun current(context: Context): Flow<String?> =
        watcherConnected
            .flatMapLatest { connected -> if (connected) fromWatcher else polled(context) }
            .distinctUntilChanged()

    private fun polled(context: Context): Flow<String?> = flow {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        var last: String? = null
        while (true) {
            if (hasUsageAccess(context) && power?.isInteractive != false) {
                val now = System.currentTimeMillis()
                val events = runCatching { usage?.queryEvents(now - WINDOW_MS, now) }.getOrNull()
                val event = UsageEvents.Event()
                while (events?.hasNextEvent() == true) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
                }
                emit(last)
            } else if (!hasUsageAccess(context)) {
                emit(null)
            }
            delay(POLL)
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
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
    private const val WINDOW_MS = 60_000L
}
