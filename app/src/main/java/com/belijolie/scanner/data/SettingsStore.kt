package com.belijolie.scanner.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class SettingsStore(private val context: Context) {

    private object Keys {
        val SHOP_NAME = stringPreferencesKey("shop_name")
        val ADDRESS = stringPreferencesKey("address")
        val ADDRESS_COMPLEMENT = stringPreferencesKey("address_complement")
        val POSTAL_CODE = stringPreferencesKey("postal_code")
        val CITY = stringPreferencesKey("city")
        val COUNTRY = stringPreferencesKey("country")
        val MICROSTORE_QR = stringPreferencesKey("microstore_qr")
        val MICROSTORE_CODE = stringPreferencesKey("microstore_code")
        val WEBSITE_URL = stringPreferencesKey("website_url")
        val GOOGLE_REVIEW_URL = stringPreferencesKey("google_review_url")
        val PRINTER_IP = stringPreferencesKey("printer_ip")
        val PRINTER_PORT = intPreferencesKey("printer_port")
        val SCANNER_ACTION = stringPreferencesKey("scanner_action")
        val SCANNER_EXTRA = stringPreferencesKey("scanner_extra")
        val EXPORT_URL = stringPreferencesKey("export_url")
        val SAGE_MANUAL_ART = stringPreferencesKey("sage_manual_article")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            shopName = prefs[Keys.SHOP_NAME] ?: AppSettings.DEFAULT.shopName,
            address = prefs[Keys.ADDRESS] ?: AppSettings.DEFAULT.address,
            addressComplement = prefs[Keys.ADDRESS_COMPLEMENT] ?: AppSettings.DEFAULT.addressComplement,
            postalCode = prefs[Keys.POSTAL_CODE] ?: AppSettings.DEFAULT.postalCode,
            city = prefs[Keys.CITY] ?: AppSettings.DEFAULT.city,
            country = prefs[Keys.COUNTRY] ?: AppSettings.DEFAULT.country,
            microstoreQr = prefs[Keys.MICROSTORE_QR] ?: AppSettings.DEFAULT.microstoreQr,
            microstoreCode = prefs[Keys.MICROSTORE_CODE] ?: AppSettings.DEFAULT.microstoreCode,
            websiteUrl = prefs[Keys.WEBSITE_URL] ?: AppSettings.DEFAULT.websiteUrl,
            googleReviewUrl = prefs[Keys.GOOGLE_REVIEW_URL] ?: AppSettings.DEFAULT.googleReviewUrl,
            printerIp = prefs[Keys.PRINTER_IP] ?: AppSettings.DEFAULT.printerIp,
            printerPort = prefs[Keys.PRINTER_PORT] ?: AppSettings.DEFAULT.printerPort,
            scannerAction = prefs[Keys.SCANNER_ACTION] ?: AppSettings.DEFAULT.scannerAction,
            scannerExtra = prefs[Keys.SCANNER_EXTRA] ?: AppSettings.DEFAULT.scannerExtra,
            exportUrl = prefs[Keys.EXPORT_URL] ?: AppSettings.DEFAULT.exportUrl,
            sageManualArticleCode = prefs[Keys.SAGE_MANUAL_ART] ?: AppSettings.DEFAULT.sageManualArticleCode
        )
    }

    suspend fun update(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHOP_NAME] = settings.shopName
            prefs[Keys.ADDRESS] = settings.address
            prefs[Keys.ADDRESS_COMPLEMENT] = settings.addressComplement
            prefs[Keys.POSTAL_CODE] = settings.postalCode
            prefs[Keys.CITY] = settings.city
            prefs[Keys.COUNTRY] = settings.country
            prefs[Keys.MICROSTORE_QR] = settings.microstoreQr
            prefs[Keys.MICROSTORE_CODE] = settings.microstoreCode
            prefs[Keys.WEBSITE_URL] = settings.websiteUrl
            prefs[Keys.GOOGLE_REVIEW_URL] = settings.googleReviewUrl
            prefs[Keys.PRINTER_IP] = settings.printerIp
            prefs[Keys.PRINTER_PORT] = settings.printerPort
            prefs[Keys.SCANNER_ACTION] = settings.scannerAction
            prefs[Keys.SCANNER_EXTRA] = settings.scannerExtra
            prefs[Keys.EXPORT_URL] = settings.exportUrl
            prefs[Keys.SAGE_MANUAL_ART] = settings.sageManualArticleCode
        }
    }
}
