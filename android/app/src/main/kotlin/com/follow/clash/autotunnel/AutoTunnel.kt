package com.follow.clash.autotunnel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.follow.clash.ServiceState
import com.follow.clash.common.GlobalState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object AutoTunnel {
    private const val PREFS = "quartz_auto_tunnel"
    private const val KEY = "settings"
    private const val SEEN = "seenApps"
    private const val FLUTTER_PREFS = "FlutterSharedPreferences"
    private const val SHARED_STATE = "flutter.sharedState"

    /** Read by Flutter on its next start, to adopt the switch made while it was closed. */
    const val APPLIED = "flutter.quartz.autoTunnel.applied"
    private const val MAX_SEEN = 50
    private val gson = Gson()

    fun settings(context: Context): AutoTunnelSettings =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let {
            runCatching { gson.fromJson(it, AutoTunnelSettings::class.java) }.getOrNull()
        } ?: AutoTunnelSettings()

    fun update(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).commit()
        KillSwitch.nativeProfileId = null
        sync(context)
        KillSwitch.evaluate()
    }

    fun sync(context: Context) {
        KillSwitch.watch()
        val intent = Intent(context, AutoTunnelService::class.java)
        val settings = settings(context)
        if (settings.enabled || settings.usesKillSwitch) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
        } else {
            context.stopService(intent)
        }
    }

    fun seenApps(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SEEN, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun rememberApp(context: Context, packageName: String, label: String) {
        val seen = seenApps(context)
        if (seen.any { it.substringBefore('\t') == packageName }) return
        val next = (listOf("$packageName\t$label") + seen).take(MAX_SEEN)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SEEN, next.joinToString("\n"))
            .apply()
    }

    /** Points the service at [key]'s rendered config and restarts it if it was running. */
    suspend fun applyWithoutFlutter(context: Context, key: String, target: TunnelTarget) {
        if (target.profileId == null) {
            KillSwitch.onManualStop()
            ServiceState.handleSwitchStop()
            return
        }
        val rendered = settings(context).rendered[key]
        val source = rendered?.path?.let(::File)
        if (rendered == null || source == null || !source.isFile) {
            GlobalState.log("Auto-tunnel: no config rendered for $key yet; open Quartz once")
            return
        }
        val prefs = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(APPLIED, null) == key && ServiceState.isRunRequested) return
        val state = prefs.getString(SHARED_STATE, null)
            ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            ?: run {
                GlobalState.log("Auto-tunnel: Quartz hasn't saved its service state yet")
                return
            }
        state.addProperty("currentProfileName", rendered.label)
        val params = state.getAsJsonObject("setupParams") ?: JsonObject()
        params.add("selected-map", gson.toJsonTree(rendered.selectedMap))
        state.add("setupParams", params)
        source.copyTo(File(context.filesDir, "config.yaml"), overwrite = true)
        prefs.edit()
            .putString(SHARED_STATE, state.toString())
            .putString(APPLIED, key)
            .commit()
        KillSwitch.nativeProfileId = rendered.profileId
        if (ServiceState.isRunRequested) ServiceState.handleSwitchStop()
        ServiceState.handleStartAction()
    }
}
