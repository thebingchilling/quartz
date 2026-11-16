package com.follow.clash

import android.app.Application
import android.content.Context
import com.follow.clash.autotunnel.AutoTunnel
import com.follow.clash.common.GlobalState

class FlClashApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        GlobalState.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        AutoTunnel.sync(this)
    }
}
