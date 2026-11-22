package com.follow.clash.plugins

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.follow.clash.autotunnel.AutoTunnel
import com.follow.clash.common.Components
import com.follow.clash.common.GlobalState
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Auto-tunnel's Flutter side: its rules, nearby Wi-Fi, SIM cards and the permissions they need. */
class AutoTunnelPlugin :
    FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var pending: MethodChannel.Result? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel =
            MethodChannel(
                flutterPluginBinding.binaryMessenger,
                "${Components.PACKAGE_NAME}/autotunnel",
            )
        channel.setMethodCallHandler(this)
        active = this
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        if (active === this) active = null
    }

    private suspend fun invokeApply(decision: String): Boolean = withContext(Dispatchers.Main) {
        val answer = CompletableDeferred<Boolean>()
        channel.invokeMethod("apply", decision, object : MethodChannel.Result {
            override fun success(result: Any?) {
                answer.complete(result == true)
            }

            override fun error(code: String, message: String?, details: Any?) {
                answer.complete(false)
            }

            override fun notImplemented() {
                answer.complete(false)
            }
        })
        withTimeoutOrNull(APPLY_TIMEOUT_MS) { answer.await() } ?: false
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = GlobalState.application
        when (call.method) {
            "update" -> {
                AutoTunnel.update(context, call.arguments as? String ?: "{}")
                result.success(true)
            }
            "permissions" -> result.success(permissions())
            "request" -> request(call.arguments as? String, result)
            "wifiNetworks" -> result.success(wifiNetworks())
            "simCards" -> result.success(simCards())
            else -> result.notImplemented()
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(GlobalState.application, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun permissions(): Map<String, Boolean> = mapOf(
        "location" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
        "backgroundLocation" to
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
        "phone" to granted(Manifest.permission.READ_PHONE_STATE),
    )

    private fun request(kind: String?, result: MethodChannel.Result) {
        val settings = when (kind) {
            "systemKillSwitch" -> Settings.ACTION_VPN_SETTINGS
            else -> null
        }
        if (settings != null) {
            val intent = Intent(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            result.success(runCatching { GlobalState.application.startActivity(intent) }.isSuccess)
            return
        }
        val permissions = when (kind) {
            "location" -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            "backgroundLocation" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else null
            "phone" -> arrayOf(Manifest.permission.READ_PHONE_STATE)
            else -> null
        }
        val activity = activity
        if (permissions == null || activity == null) {
            result.success(permissions == null)
            return
        }
        pending?.success(false)
        pending = result
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        pending?.success(
            grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        )
        pending = null
        return true
    }

    /** The connected network first, then nearby ones from the last scan. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<String> {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()
        val wifi = GlobalState.application.getSystemService(WifiManager::class.java)
            ?: return emptyList()
        val current = wifi.connectionInfo?.ssid?.removeSurrounding("\"")
        runCatching { wifi.startScan() }
        val nearby = runCatching { wifi.scanResults }.getOrNull().orEmpty().mapNotNull {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                it.wifiSsid?.toString()?.removeSurrounding("\"")
            else it.SSID
            ssid?.takeIf { name -> name.isNotBlank() }
        }
        return (listOfNotNull(current?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }) +
            nearby).distinct()
    }

    @SuppressLint("MissingPermission")
    private fun simCards(): List<Map<String, Any?>> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val manager = GlobalState.application.getSystemService(SubscriptionManager::class.java)
            ?: return emptyList()
        return runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty().map {
            mapOf(
                "id" to it.subscriptionId,
                "name" to (it.displayName?.toString() ?: it.carrierName?.toString()),
                "slot" to it.simSlotIndex,
            )
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        this.binding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        binding?.removeRequestPermissionsResultListener(this)
        binding = null
        activity = null
    }

    companion object {
        private const val REQUEST_CODE = 7461
        private const val APPLY_TIMEOUT_MS = 30_000L

        @Volatile
        private var active: AutoTunnelPlugin? = null

        /** False when Quartz's UI isn't running or couldn't switch; the caller then switches itself. */
        suspend fun apply(decision: String): Boolean = active?.invokeApply(decision) ?: false
    }
}
