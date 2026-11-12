package app.quartz.apprules

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import org.koin.android.ext.android.inject

/**
 * Quartz's app detection for auto-tunnel's app rules (FORK.md §14): an accessibility service that
 * only listens for which app's window comes to the front (no window content, no gestures). Apps in
 * the work profile, private space and Secure Folder share the screen, so it sees them too, which
 * usage access can't. Apps the app list can't show (private space, Secure Folder) are remembered
 * when seen, so they can be picked in a rule.
 */
class QuartzAppWatcherService : AccessibilityService() {
    private val rules: AppRules by inject()
    private var ignored: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ignored = buildSet {
            add(packageName)
            add("android")
            add("com.android.systemui")
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.forEach { add(it.packageName) }
        }
        ForegroundApp.onWatcherConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored) return
        val installedHere = isInstalled(pkg)
        // Dialogs, toasts and pop-ups also change window state; an app coming to the front is an
        // activity (checked where the app is installed in this profile) or a full-screen window.
        val isActivity =
            installedHere &&
                event.className?.let { cls ->
                    runCatching {
                            packageManager.getActivityInfo(ComponentName(pkg, cls.toString()), 0)
                        }
                        .isSuccess
                } == true
        if (!isActivity && !event.isFullScreen) return
        ForegroundApp.onWatcherApp(pkg)
        if (!installedHere) {
            val title = event.text.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
            rules.remember(AppRef(pkg, title ?: pkg, AppProfile.OTHER, user = -1))
        }
    }

    /** Installed in the main profile (an app only in another profile isn't found here). */
    private fun isInstalled(pkg: String): Boolean =
        runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ForegroundApp.onWatcherConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ForegroundApp.onWatcherConnected(false)
        super.onDestroy()
    }
}
