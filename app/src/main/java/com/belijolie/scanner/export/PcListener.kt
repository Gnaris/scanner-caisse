package com.belijolie.scanner.export

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ni le Wi-Fi ni le câble USB ne mènent au Scanner Listener du PC. */
class PcUnreachableException(message: String) : IOException(message)

/**
 * Trouve le chemin vers le Scanner Listener (logiciel PC) :
 *  1. par le Wi-Fi, à l'adresse de « URL export PC » des Paramètres ;
 *  2. en secours, par le CÂBLE USB : quand le PDA est branché au PC,
 *     Scanner Listener ouvre un tunnel `adb reverse` et le PC répond alors
 *     sur http://127.0.0.1:<port> côté PDA (débogage USB activé et autorisé).
 *
 * Permet de continuer à créer les factures Sage et les exports (Excel,
 * JSON, ticket PDF) même quand le Wi-Fi de la boutique est en panne.
 */
object PcListener {

    private const val DEFAULT_PORT = 8080

    /** Base `http://hôte:port` joignable, ou [PcUnreachableException]. */
    fun resolveBase(exportUrl: String): String {
        val wifi = runCatching { URL(exportUrl.trim()) }.getOrNull()
        val port = wifi?.port?.takeIf { it > 0 } ?: DEFAULT_PORT
        val candidates = buildList {
            if (wifi != null) add("${wifi.protocol}://${wifi.host}:$port" to 3_000)
            add("http://127.0.0.1:$port" to 1_500)
        }
        for ((base, timeout) in candidates) {
            if (answers(base, timeout)) return base
        }
        throw PcUnreachableException(
            if (wifi == null)
                "PC introuvable : renseignez « URL export PC » dans les Paramètres, ou branchez le PDA au PC en USB."
            else
                "PC injoignable par le Wi-Fi. Branchez le PDA au PC avec le câble USB (Scanner Listener ouvert) puis réessayez."
        )
    }

    /** Chemin + requête de l'URL d'export (« /export » par défaut). */
    fun exportPath(exportUrl: String): String {
        val u = runCatching { URL(exportUrl.trim()) }.getOrNull() ?: return "/export"
        val path = u.path.ifBlank { "/export" }
        return if (u.query.isNullOrBlank()) path else "$path?${u.query}"
    }

    /** N'importe quelle réponse HTTP (même 404 d'un ancien Listener) suffit. */
    private fun answers(base: String, timeoutMs: Int): Boolean {
        val conn = runCatching { URL("$base/ping").openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 4_000
            conn.responseCode > 0
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
