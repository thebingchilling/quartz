package com.follow.clash.service.modules

import android.content.Context

/**
 * Feeds the Core the phone's DNS servers for the whole life of the process. The VPN's own observer
 * only runs while connected, and with no servers the Core's system DNS answers nothing, so a proxy
 * server given by hostname could not be reached for a delay test until the first connect.
 */
object SystemDnsWatcher {
    private var module: NetworkObserveModule? = null

    @Synchronized
    fun start(context: Context) {
        if (module != null) return
        module = NetworkObserveModule(context.applicationContext, ownsNetwork = false).also {
            it.start()
        }
    }
}
