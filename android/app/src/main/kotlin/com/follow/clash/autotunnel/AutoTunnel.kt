package com.follow.clash.autotunnel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.Gson

/** Stores the rules Flutter sends and keeps [AutoTunnelService] running while they're on. */
object AutoTunnel {
    private const val PREFS = "quartz_auto_tunnel"
    private const val KEY = "settings"
    private val gson = Gson()

    fun settings(context: Context): AutoTunnelSettings =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let {
            runCatching { gson.fromJson(it, AutoTunnelSettings::class.java) }.getOrNull()
        } ?: AutoTunnelSettings()

    fun update(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        sync(context)
    }

    fun sync(context: Context) {
        val intent = Intent(context, AutoTunnelService::class.java)
        if (settings(context).enabled) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
        } else {
            context.stopService(intent)
        }
    }
}
