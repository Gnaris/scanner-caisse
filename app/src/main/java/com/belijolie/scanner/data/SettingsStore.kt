package com.belijolie.scanner.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.belijolie.scanner.data.entity.SharedSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val shopName: String,
    val address: String,
    val addressComplement: String,
    val postalCode: String,
    val city: String,
    val country: String,
    val microstoreQr: String,
    val microstoreCode: String,
    val websiteUrl: String,
    val googleReviewUrl: String,
    val printerIp: String,
    val printerPort: Int,
    val scannerAction: String,
    val scannerExtra: String,
    val exportUrl: String,
    /** Code article Sage utilisé pour les produits ajoutés manuellement dans
     *  un canal (sans code-barre). Vide = laisse Sage demander à la validation. */
    val sageManualArticleCode: String
) {
    companion object {
        val DEFAULT = AppSettings(
            shopName = "Beli & Jolie",
            address = "90 rue de la Haie Coq",
            addressComplement = "",
            postalCode = "93300",
            city = "Aubervilliers",
            country = "France",
            microstoreQr = "",
            microstoreCode = "",
            websiteUrl = "",
            googleReviewUrl = "",
            printerIp = "192.168.1.31",
            printerPort = 9100,
            scannerAction = "com.android.serial.BARCODEPORT_RECEIVEDDATA_ACTION",
            scannerExtra = "data",
            exportUrl = "",
            sageManualArticleCode = ""
        )
    }
}

/**
 * Paramètres de l'app, répartis en deux familles :
 *
 *  - **Partagés** (identité du commerce, QR, code Sage manuel) : table Room
 *    `settings`, synchronisée entre tous les appareils.
 *  - **Propres au PDA** (imprimante, scanner, URL d'export vers le PC) :
 *    DataStore local, jamais synchronisés.
 *
 * L'appelant voit toujours un [AppSettings] complet et n'a pas à savoir où
 * vit chaque champ.
 */
class SettingsStore(private val context: Context, private val db: AppDatabase) {

    /** Clés des paramètres partagés (= id des lignes de la table `settings`). */
    private object SharedKeys {
        const val SHOP_NAME = "shop_name"
        const val ADDRESS = "address"
        const val ADDRESS_COMPLEMENT = "address_complement"
        const val POSTAL_CODE = "postal_code"
        const val CITY = "city"
        const val COUNTRY = "country"
        const val MICROSTORE_QR = "microstore_qr"
        const val MICROSTORE_CODE = "microstore_code"
        const val WEBSITE_URL = "website_url"
        const val GOOGLE_REVIEW_URL = "google_review_url"
        const val SAGE_MANUAL_ART = "sage_manual_article"

        val ALL = listOf(
            SHOP_NAME, ADDRESS, ADDRESS_COMPLEMENT, POSTAL_CODE, CITY, COUNTRY,
            MICROSTORE_QR, MICROSTORE_CODE, WEBSITE_URL, GOOGLE_REVIEW_URL, SAGE_MANUAL_ART,
        )
    }

    private object LocalKeys {
        val PRINTER_IP = stringPreferencesKey("printer_ip")
        val PRINTER_PORT = intPreferencesKey("printer_port")
        val SCANNER_ACTION = stringPreferencesKey("scanner_action")
        val SCANNER_EXTRA = stringPreferencesKey("scanner_extra")
        val EXPORT_URL = stringPreferencesKey("export_url")
        // Paramètres partagés déjà recopiés de DataStore vers Room (v7)
        val SHARED_MIGRATED = booleanPreferencesKey("shared_migrated_to_room")
    }

    val settings: Flow<AppSettings> =
        combine(context.dataStore.data, db.settingDao().observeAll()) { prefs, shared ->
            build(prefs, shared.associate { it.id to it.value })
        }

    private fun build(prefs: Preferences, shared: Map<String, String>): AppSettings {
        val d = AppSettings.DEFAULT
        return AppSettings(
            shopName = shared[SharedKeys.SHOP_NAME] ?: d.shopName,
            address = shared[SharedKeys.ADDRESS] ?: d.address,
            addressComplement = shared[SharedKeys.ADDRESS_COMPLEMENT] ?: d.addressComplement,
            postalCode = shared[SharedKeys.POSTAL_CODE] ?: d.postalCode,
            city = shared[SharedKeys.CITY] ?: d.city,
            country = shared[SharedKeys.COUNTRY] ?: d.country,
            microstoreQr = shared[SharedKeys.MICROSTORE_QR] ?: d.microstoreQr,
            microstoreCode = shared[SharedKeys.MICROSTORE_CODE] ?: d.microstoreCode,
            websiteUrl = shared[SharedKeys.WEBSITE_URL] ?: d.websiteUrl,
            googleReviewUrl = shared[SharedKeys.GOOGLE_REVIEW_URL] ?: d.googleReviewUrl,
            sageManualArticleCode = shared[SharedKeys.SAGE_MANUAL_ART] ?: d.sageManualArticleCode,
            printerIp = prefs[LocalKeys.PRINTER_IP] ?: d.printerIp,
            printerPort = prefs[LocalKeys.PRINTER_PORT] ?: d.printerPort,
            scannerAction = prefs[LocalKeys.SCANNER_ACTION] ?: d.scannerAction,
            scannerExtra = prefs[LocalKeys.SCANNER_EXTRA] ?: d.scannerExtra,
            exportUrl = prefs[LocalKeys.EXPORT_URL] ?: d.exportUrl,
        )
    }

    private fun sharedValues(s: AppSettings): Map<String, String> = mapOf(
        SharedKeys.SHOP_NAME to s.shopName,
        SharedKeys.ADDRESS to s.address,
        SharedKeys.ADDRESS_COMPLEMENT to s.addressComplement,
        SharedKeys.POSTAL_CODE to s.postalCode,
        SharedKeys.CITY to s.city,
        SharedKeys.COUNTRY to s.country,
        SharedKeys.MICROSTORE_QR to s.microstoreQr,
        SharedKeys.MICROSTORE_CODE to s.microstoreCode,
        SharedKeys.WEBSITE_URL to s.websiteUrl,
        SharedKeys.GOOGLE_REVIEW_URL to s.googleReviewUrl,
        SharedKeys.SAGE_MANUAL_ART to s.sageManualArticleCode,
    )

    suspend fun update(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[LocalKeys.PRINTER_IP] = settings.printerIp
            prefs[LocalKeys.PRINTER_PORT] = settings.printerPort
            prefs[LocalKeys.SCANNER_ACTION] = settings.scannerAction
            prefs[LocalKeys.SCANNER_EXTRA] = settings.scannerExtra
            prefs[LocalKeys.EXPORT_URL] = settings.exportUrl
        }
        // N'écrit (et donc ne synchronise) que les paramètres partagés qui ont
        // réellement changé : enregistrer l'écran Paramètres ne doit pas
        // écraser ce qu'un autre appareil vient de modifier sur un autre champ.
        val before = sharedValues(this.settings.first())
        for ((key, value) in sharedValues(settings)) {
            if (before[key] != value) db.settingDao().save(key, value)
        }
    }

    /**
     * Une seule fois après la mise à jour v7 : recopie les paramètres
     * partagés de DataStore (ancien stockage) vers la table synchronisée.
     * updatedAt = 0 → une vraie modification faite ailleurs l'emporte.
     */
    suspend fun migrateLegacySharedSettings() {
        val prefs = context.dataStore.data.first()
        if (prefs[LocalKeys.SHARED_MIGRATED] == true) return
        val existing = db.settingDao().getAll().map { it.id }.toSet()
        for (key in SharedKeys.ALL) {
            val value = prefs[stringPreferencesKey(key)] ?: continue
            if (key in existing) continue
            db.settingDao().upsertRaw(SharedSetting(id = key, value = value, updatedAt = 0, dirty = true))
        }
        context.dataStore.edit { it[LocalKeys.SHARED_MIGRATED] = true }
    }
}
