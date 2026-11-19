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

    @Volatile
    private var cached: AutoTunnelSettings? = null

    /** Read on every network callback, which Android sends often, so parsed once per update. */
    fun settings(context: Context): AutoTunnelSettings = cached ?: (
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let {
            runCatching { gson.fromJson(it, AutoTunnelSettings::class.java) }.getOrNull()
        } ?: AutoTunnelSettings()
        ).also { cached = it }

    fun update(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).commit()
        cached = null
        sync(context)
        KillSwitch.evaluate()
    }

    fun sync(context: Context) {
        KillSwitch.watch()
        val intent = Intent(context, AutoTunnelService::class.java)
        val settings = settings(context)
        if (settings.enabled) {
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

    fun toJson(decision: Decision): String = gson.toJson(decision)

    /** Points the service at the decision's rendered config and restarts it if it was running. */
    suspend fun applyWithoutFlutter(context: Context, decision: Decision) {
        if (decision.connect == null) return
        if (decision.connect == false) {
            if (ServiceState.isRunRequested) ServiceState.handleSwitchStop()
            return
        }
        if (decision.target == null && ServiceState.isRunRequested) return
        val settings = settings(context)
        val target = decision.target ?: settings.currentProfileId?.let { TunnelTarget(profileId = it) }
        val rendered = target?.let { settings.rendered[it.key] }
        val source = rendered?.path?.let(::File)
        if (rendered == null || source == null || !source.isFile) {
            if (decision.target == null) {
                ServiceState.handleStartAction()
                return
            }
            GlobalState.log("Auto-tunnel: no config rendered for ${decision.key} yet; open Quartz once")
            return
        }
        val prefs = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
        val applied = toJson(decision)
        if (prefs.getString(APPLIED, null) == applied && ServiceState.isRunRequested) return
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
            .putString(APPLIED, applied)
            .commit()
        if (ServiceState.isRunRequested) ServiceState.handleSwitchStop()
        AutoTunnelState.expectStart()
        ServiceState.handleStartAction()
    }
}
