package com.follow.clash.autotunnel

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/** Sees work-profile and Secure Folder apps too, which usage access can't. */
class AppWatcherService : AccessibilityService() {
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
        val installedHere = runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        val isActivity = installedHere && event.className?.let { cls ->
            runCatching {
                packageManager.getActivityInfo(ComponentName(pkg, cls.toString()), 0)
            }.isSuccess
        } == true
        if (!isActivity && !event.isFullScreen) return
        ForegroundApp.onWatcherApp(pkg)
        if (!installedHere) {
            val title = event.text.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
            AutoTunnel.rememberApp(this, pkg, title ?: pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundApp.onWatcherConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ForegroundApp.onWatcherConnected(false)
        super.onDestroy()
    }
}
