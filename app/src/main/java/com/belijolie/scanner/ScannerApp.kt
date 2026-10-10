package com.belijolie.scanner

import android.app.Application
import com.belijolie.scanner.cart.ChannelsRepository
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.SettingsStore

class ScannerApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var channelsRepo: ChannelsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.get(this)
        settingsStore = SettingsStore(this)
        channelsRepo = ChannelsRepository(this)
    }

    companion object {
        lateinit var instance: ScannerApp
            private set
    }
}
