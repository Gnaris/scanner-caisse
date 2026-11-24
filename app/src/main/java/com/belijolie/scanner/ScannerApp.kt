package com.belijolie.scanner

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.belijolie.scanner.scanner.ScanEventBus
import com.belijolie.scanner.sync.SyncEngine
import kotlinx.coroutines.withContext
import com.belijolie.scanner.cart.ChannelsRepository
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.DeviceSession
import com.belijolie.scanner.data.SettingsStore
import com.belijolie.scanner.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScannerApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var session: DeviceSession
        private set
    lateinit var channelsRepo: ChannelsRepository
        private set
    lateinit var updateManager: UpdateManager
        private set
    lateinit var syncEngine: SyncEngine
        private set

    /** Scope de l'application (tâches de fond qui survivent aux écrans). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.get(this)
        settingsStore = SettingsStore(this, db)
        session = DeviceSession(this, db)
        channelsRepo = ChannelsRepository(this, db, session)
        updateManager = UpdateManager(this)
        syncEngine = SyncEngine(this, db, session)
        syncEngine.start(appScope)

        // QR de configuration de la synchro scanné depuis n'importe quel écran
        appScope.launch {
            ScanEventBus.events.collect { text ->
                if (syncEngine.handleConfigScan(text)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScannerApp, R.string.sync_configured_toast, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Reprise unique des données de l'ancienne version (avant v7).
        appScope.launch {
            runCatching { settingsStore.migrateLegacySharedSettings() }
                .onFailure { Log.e("ScannerApp", "Reprise des paramètres", it) }
            // Les canaux en cours sont rattachés à la première caisse ouverte.
            val deviceId = session.currentDeviceId.filterNotNull().first()
            runCatching { channelsRepo.importLegacyChannels(deviceId) }
                .onFailure { Log.e("ScannerApp", "Reprise des canaux", it) }
        }
    }

    companion object {
        lateinit var instance: ScannerApp
            private set
    }
}
