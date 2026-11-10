package com.smhasnan.openvpn

import android.app.Application
import android.content.Context

class OpenVpnApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as OpenVpnApplication).container
