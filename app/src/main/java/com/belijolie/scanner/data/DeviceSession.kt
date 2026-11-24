package com.belijolie.scanner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.belijolie.scanner.data.entity.Device
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.sessionStore by preferencesDataStore(name = "session")

/**
 * Caisse ouverte sur CE PDA (« Appareil 1 », « Appareil 2 »…) + identifiant
 * technique du PDA physique. Pas une authentification : juste le choix de la
 * caisse dont on veut voir les canaux.
 */
class DeviceSession(private val context: Context, private val db: AppDatabase) {

    private object Keys {
        val CURRENT_DEVICE = stringPreferencesKey("current_device_id")
        val INSTALL_ID = stringPreferencesKey("install_id")
    }

    /** Id de la caisse ouverte, null si aucune choisie. */
    val currentDeviceId: Flow<String?> = context.sessionStore.data.map { it[Keys.CURRENT_DEVICE] }

    /**
     * Caisse ouverte, null si aucune choisie OU si elle a été supprimée
     * (ici ou sur un autre appareil) → retour à l'écran de choix.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentDevice: Flow<Device?> = currentDeviceId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else db.deviceDao().observeAll().map { list -> list.firstOrNull { it.id == id } }
    }

    val devices: Flow<List<Device>> = db.deviceDao().observeAll()

    suspend fun select(deviceId: String) {
        context.sessionStore.edit { it[Keys.CURRENT_DEVICE] = deviceId }
    }

    suspend fun leave() {
        context.sessionStore.edit { it.remove(Keys.CURRENT_DEVICE) }
    }

    /** Ajoute une caisse. Si le nom existe déjà, renvoie l'existante. */
    suspend fun addDevice(name: String): Device {
        val cleaned = name.trim().replace(Regex("\\s+"), " ")
        db.deviceDao().findByName(cleaned)?.let { return it }
        val device = Device(name = cleaned)
        db.deviceDao().save(device)
        return device
    }

    /** Supprime une caisse et tous ses canaux ouverts (sur tous les appareils). */
    suspend fun deleteDevice(deviceId: String) {
        db.withTransaction {
            db.channelDao().softDeleteLinesOfDevice(deviceId)
            db.channelDao().softDeleteForDevice(deviceId)
            db.deviceDao().softDelete(deviceId)
        }
        if (currentDeviceId.first() == deviceId) leave()
    }

    /** Identifiant stable de ce PDA physique (envoyé au serveur avec chaque synchro). */
    suspend fun installId(): String {
        context.sessionStore.data.first()[Keys.INSTALL_ID]?.let { return it }
        val id = newId()
        context.sessionStore.edit { prefs ->
            if (prefs[Keys.INSTALL_ID] == null) prefs[Keys.INSTALL_ID] = id
        }
        return context.sessionStore.data.first()[Keys.INSTALL_ID]!!
    }
}
