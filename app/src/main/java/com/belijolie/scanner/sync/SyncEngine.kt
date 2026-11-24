package com.belijolie.scanner.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.DeviceSession
import com.belijolie.scanner.util.isNetworkConnected
import com.belijolie.scanner.util.toHumanMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private val Context.syncStore by preferencesDataStore(name = "sync")

data class SyncConfig(val serverUrl: String, val apiKey: String) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

sealed class SyncStatus {
    data object NotConfigured : SyncStatus()
    data object Offline : SyncStatus()
    data object Syncing : SyncStatus()
    data class Synced(val atMs: Long) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

private class SyncHttpException(val code: Int, message: String) : IOException(message)

/**
 * Synchronisation « hors-ligne d'abord » avec le serveur central
 * (server/, https://caisse.beliandjolie.com).
 *
 * L'app lit et écrit TOUJOURS dans la base locale ; ce moteur, en tâche de
 * fond :
 *  1. envoie les lignes marquées « à envoyer » (dirty) — POST /v1/sync/push ;
 *  2. reçoit tout ce qui a changé depuis la dernière version connue —
 *     GET /v1/sync/pull?since=N (par pages).
 *
 * Déclencheurs : au démarrage, ~1,5 s après chaque modification locale, au
 * retour du réseau, et toutes les 30 s (pour voir les scans des autres PDA).
 */
class SyncEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val session: DeviceSession,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val LAST_VERSION = longPreferencesKey("last_version")
        val LAST_SUCCESS = longPreferencesKey("last_success_ms")
    }

    private val tables = syncTables(db)
    private val mutex = Mutex()
    private lateinit var scope: CoroutineScope

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val config: Flow<SyncConfig> = context.syncStore.data.map {
        SyncConfig(it[Keys.SERVER_URL] ?: DEFAULT_URL, it[Keys.API_KEY].orEmpty())
    }

    suspend fun setConfig(serverUrl: String, apiKey: String) {
        val url = serverUrl.trim().trimEnd('/').ifBlank { DEFAULT_URL }
        val old = config.first()
        context.syncStore.edit {
            it[Keys.SERVER_URL] = url
            it[Keys.API_KEY] = apiKey.trim()
            // Autre serveur → on repart de zéro (tout renvoyer, tout relire)
            if (old.serverUrl != url) it[Keys.LAST_VERSION] = 0
        }
        if (old.serverUrl != url) markEverythingDirty()
        requestSync()
    }

    /**
     * QR de configuration scanné avec le scanner du PDA :
     * `BJSYNC|https://caisse.beliandjolie.com|<clé>`. Renvoie true si le
     * texte était bien un QR de configuration (et ne doit pas être traité
     * comme un code-barres produit).
     */
    suspend fun handleConfigScan(text: String): Boolean {
        if (!isConfigScan(text)) return false
        val parts = text.split('|')
        if (parts.size != 3 || parts[2].isBlank()) return true
        setConfig(parts[1], parts[2])
        return true
    }

    fun start(appScope: CoroutineScope) {
        scope = appScope
        // Après chaque modification locale (avec un court délai pour grouper)
        @OptIn(FlowPreview::class)
        appScope.launch {
            db.syncDao().observeDirtyCount()
                .filter { it > 0 }
                .debounce(1_500)
                .collect { sync() }
        }
        // Régulièrement : récupère les changements des autres appareils
        appScope.launch {
            while (true) {
                sync()
                delay(PERIOD_MS)
            }
        }
        // Retour du réseau
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { requestSync() }
            })
        }
    }

    fun requestSync() {
        if (::scope.isInitialized) scope.launch { sync() }
    }

    /** Une synchronisation complète (envoi puis réception). */
    suspend fun sync() = mutex.withLock {
        val cfg = config.first()
        if (!cfg.isConfigured) {
            _status.value = SyncStatus.NotConfigured
            return@withLock
        }
        if (!isNetworkConnected(context)) {
            _status.value = SyncStatus.Offline
            return@withLock
        }
        if (_status.value !is SyncStatus.Synced) _status.value = SyncStatus.Syncing
        try {
            withContext(Dispatchers.IO) {
                push(cfg)
                pull(cfg)
            }
            val now = System.currentTimeMillis()
            context.syncStore.edit { it[Keys.LAST_SUCCESS] = now }
            _status.value = SyncStatus.Synced(now)
        } catch (e: SyncHttpException) {
            Log.w(TAG, "Synchro refusée (${e.code})", e)
            _status.value = SyncStatus.Error(
                if (e.code == 401) "Clé de synchronisation invalide" else "Serveur : ${e.message}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Synchro impossible", e)
            _status.value = if (!isNetworkConnected(context)) SyncStatus.Offline
            else SyncStatus.Error(e.toHumanMessage(context))
        }
    }

    // ── Envoi ───────────────────────────────────────────────────────────

    private suspend fun push(cfg: SyncConfig) {
        val origin = session.installId()
        repeat(MAX_PUSH_ROUNDS) {
            val changes = JSONObject()
            val sent = mutableListOf<Triple<SyncTable<*>, String, Long>>()
            for (t in tables) {
                val rows = t.dirty(PUSH_PER_TABLE)
                if (rows.isEmpty()) continue
                changes.put(t.name, org.json.JSONArray().also { arr ->
                    rows.forEach { r -> arr.put(t.toJsonAny(r)); sent += Triple(t, t.idOfAny(r), t.updatedAtOfAny(r)) }
                })
            }
            if (sent.isEmpty()) return
            val res = http(cfg, "POST", "/v1/sync/push", JSONObject().put("origin", origin).put("changes", changes))
            db.withTransaction {
                // 1. Ce qui a été envoyé n'est plus « à envoyer » (sauf si
                //    modifié pendant l'envoi : updatedAt différent).
                for ((t, id, at) in sent) t.clean(id, at)
                // 2. Doublons fusionnés par le serveur
                applyRemaps(res.optJSONObject("remaps"))
                // 3. Versions officielles (modif refusée car plus ancienne, fusion…)
                applyChanges(res.optJSONObject("overrides"))
            }
            val rejected = res.optJSONArray("rejected")
            if (rejected != null && rejected.length() > 0) Log.w(TAG, "Refusés par le serveur : $rejected")
        }
    }

    private suspend fun applyRemaps(remaps: JSONObject?) {
        if (remaps == null) return
        val s = db.syncDao()
        val currentDevice = session.currentDeviceId.first()
        for (table in remaps.keys()) {
            val map = remaps.getJSONObject(table)
            for (from in map.keys()) {
                val to = map.getString(from)
                when (table) {
                    "devices" -> {
                        s.remapDevice(from, to); s.hardDeleteDevice(from)
                        if (currentDevice == from) session.select(to)
                    }
                    "categories" -> { s.remapCategory(from, to); s.hardDeleteCategory(from) }
                    "products" -> { s.remapProduct(from, to); s.hardDeleteProduct(from) }
                    "channels" -> { s.remapChannel(from, to); s.hardDeleteChannel(from) }
                    "cart_lines" -> s.hardDeleteCartLine(from)
                    "settings" -> s.hardDeleteSetting(from)
                }
            }
        }
    }

    // ── Réception ───────────────────────────────────────────────────────

    private suspend fun pull(cfg: SyncConfig) {
        repeat(MAX_PULL_PAGES) {
            val since = context.syncStore.data.first()[Keys.LAST_VERSION] ?: 0L
            val res = http(cfg, "GET", "/v1/sync/pull?since=$since", null)
            if (res.optBoolean("reset")) {
                // Base serveur réinitialisée : on lui renvoie tout ce qu'on a.
                markEverythingDirty()
                context.syncStore.edit { it[Keys.LAST_VERSION] = 0 }
                push(cfg)
                return@repeat
            }
            db.withTransaction { applyChanges(res.optJSONObject("changes")) }
            context.syncStore.edit { it[Keys.LAST_VERSION] = res.getLong("version") }
            if (!res.optBoolean("hasMore")) return
        }
    }

    /**
     * Applique des lignes reçues du serveur. Une modification locale plus
     * récente et pas encore envoyée est conservée (elle partira au prochain
     * envoi et le serveur tranchera).
     */
    private suspend fun applyChanges(changes: JSONObject?) {
        if (changes == null) return
        for (t in tables) {
            val arr = changes.optJSONArray(t.name) ?: continue
            for (i in 0 until arr.length()) t.applyIncoming(arr.getJSONObject(i))
        }
    }

    private suspend fun markEverythingDirty() {
        db.withTransaction { tables.forEach { it.allDirty() } }
    }

    // ── HTTP ────────────────────────────────────────────────────────────

    private fun http(cfg: SyncConfig, method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(cfg.serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw SyncHttpException(code, msg)
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_URL = "https://caisse.beliandjolie.com"
        private const val TAG = "SyncEngine"
        private const val PERIOD_MS = 30_000L
        private const val PUSH_PER_TABLE = 800
        private const val MAX_PUSH_ROUNDS = 20
        private const val MAX_PULL_PAGES = 200

        fun isConfigScan(text: String): Boolean = text.startsWith("BJSYNC|")
    }
}

// Helpers génériques : permettent d'utiliser une SyncTable<*> sans connaître T.
@Suppress("UNCHECKED_CAST")
private fun <T : Any> SyncTable<T>.toJsonAny(row: Any): JSONObject = toJson(row as T)
@Suppress("UNCHECKED_CAST")
private fun <T : Any> SyncTable<T>.idOfAny(row: Any): String = idOf(row as T)
@Suppress("UNCHECKED_CAST")
private fun <T : Any> SyncTable<T>.updatedAtOfAny(row: Any): Long = updatedAtOf(row as T)

private suspend fun <T : Any> SyncTable<T>.applyIncoming(o: JSONObject) {
    val incoming = fromJson(o)
    val local = local(idOf(incoming))
    if (local != null && isDirty(local) && updatedAtOf(local) > updatedAtOf(incoming)) return
    upsert(incoming)
}
