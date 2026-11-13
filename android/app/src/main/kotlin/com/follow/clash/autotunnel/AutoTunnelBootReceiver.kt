package com.follow.clash.autotunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoTunnelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AutoTunnel.sync(context)
        }
    }
}
