package com.belijolie.scanner.export

import com.belijolie.scanner.cart.CartLine
import com.belijolie.scanner.cart.Channel
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.ui.checkout.sortLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat { CSV, JSON, SAGE }

/**
 * Erreur spécifique à l'export Sage, remontée AVANT la tentative réseau quand
 * des codes articles manquent (catégorie sans sageArticleCode / aucun
 * sageManualArticleCode configuré alors qu'il y a des lignes manuelles).
 * Le ViewModel peut la détecter si besoin, mais côté UI on affiche juste
 * son message tel quel — il est déjà rédigé pour l'utilisateur.
 */
class SageExportValidationException(message: String) : IllegalStateException(message)

/**
 * Envoie le contenu d'un canal vers l'écoute HTTP du PC. Trois formats :
 *  - [ExportFormat.CSV] : CSV UTF-8 avec BOM, ouverture directe dans Excel.
 *  - [ExportFormat.JSON] : JSON brut (produits + totaux), pour intégration.
 *  - [ExportFormat.SAGE] : CSV paramétrable "Import des pièces" Sage 50,
 *      encodé Windows-1252, descriptions RTF — importable tel quel.
 */
object ChannelExporter {

    private const val TIMEOUT_MS = 10_000
    private const val VAT_RATE = 0.20
    private const val SAGE_CLIENT_CODE = "003"
    private const val SAGE_TVA_RATE = "20,00"

    suspend fun exportChannel(
        settings: AppSettings,
        channel: Channel,
        format: ExportFormat
    ): Result<String> = withContext(Dispatchers.IO) {
        if (settings.exportUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Configure l'URL d'export dans les paramètres")
            )
        }

        // Validation spécifique Sage avant tout appel réseau.
        if (format == ExportFormat.SAGE) {
            val validationError = validateSage(channel.lines, settings.sageManualArticleCode.trim())
            if (validationError != null) {
                return@withContext Result.failure(SageExportValidationException(validationError))
            }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
        val safeName = channel.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "canal" }
        val prefix = when (format) {
            ExportFormat.CSV -> "canal"
            ExportFormat.JSON -> "canal-json"
            ExportFormat.SAGE -> "sage"
        }
        val extension = when (format) {
            ExportFormat.JSON -> "json"
            else -> "csv"
        }
        val filename = "$prefix-$safeName-$timestamp.$extension"
        val (payload, contentType) = when (format) {
            ExportFormat.CSV -> buildCsv(channel).toByteArray(Charsets.UTF_8) to "text/csv; charset=utf-8"
            ExportFormat.JSON -> buildJson(channel).toByteArray(Charsets.UTF_8) to "application/json; charset=utf-8"
            ExportFormat.SAGE -> buildSageCsv(channel, settings.sageManualArticleCode.trim())
                .toByteArray(Charset.forName("windows-1252")) to "text/csv; charset=windows-1252"
        }

        runCatching {
            val url = URL(
                settings.exportUrl + delimiterForQuery(settings.exportUrl)
                    + "filename=" + URLEncoder.encode(filename, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Content-Type", contentType)
                conn.setRequestProperty("X-Filename", filename)
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                filename
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Vérifie que le panier peut être exporté vers Sage. Retourne un message
     * d'erreur formaté (plusieurs lignes) ou null si tout est OK.
     *
     * Règles :
     *  - Toute ligne manuelle (barcode vide) nécessite [manualArticleCode]
     *    non vide dans les paramètres.
     *  - Toute ligne scannée (barcode non vide) nécessite [CartLine.sageArticleCode]
     *    non vide (= sa catégorie a été configurée en DB).
     */
    private fun validateSage(lines: List<CartLine>, manualArticleCode: String): String? {
        val hasManualLine = lines.any { it.barcode.isBlank() }
        val categoriesMissingCode = lines
            .filter { it.barcode.isNotBlank() && it.sageArticleCode.isNullOrBlank() }
            .map { it.categoryName }
            .distinct()

        val errors = mutableListOf<String>()
        if (hasManualLine && manualArticleCode.isBlank()) {
            errors += "• Des produits ont été ajoutés manuellement au panier, mais aucun " +
                "« Code article Sage manuel » n'est renseigné dans les paramètres."
        }
        if (categoriesMissingCode.isNotEmpty()) {
            errors += "• Les catégories suivantes n'ont pas de code article Sage : " +
                categoriesMissingCode.joinToString(", ") { "« $it »" } + "."
        }

        return if (errors.isEmpty()) null else buildString {
            appendLine("Impossible d'exporter vers Sage 50 :")
            appendLine()
            errors.forEach { appendLine(it) }
            appendLine()
            append("Renseigne les codes articles manquants puis relance l'export.")
        }.trimEnd()
    }

    private fun delimiterForQuery(url: String): String =
        if (url.contains('?')) "&" else "?"

    // ---------------- CSV Excel ----------------

    private fun buildCsv(channel: Channel): String = buildString {
        append('﻿') // BOM UTF-8 → Excel détecte correctement l'encodage
        append("Catégorie;Code-barres;Quantité;Prix unitaire HT (EUR);Total HT (EUR);TVA (EUR);Total TTC (EUR)\r\n")
        var totalQty = 0
        var totalHt = 0
        var totalTva = 0
        var totalTtc = 0
        sortLines(channel.lines).forEach { line ->
            val lineHt = line.unitPriceCents * line.quantity
            val lineTva = Math.round(lineHt * VAT_RATE).toInt()
            val lineTtc = lineHt + lineTva
            totalQty += line.quantity
            totalHt += lineHt
            totalTva += lineTva
            totalTtc += lineTtc
            append(csvEscape(line.categoryName)).append(';')
            append(csvEscape(line.barcode)).append(';')
            append(line.quantity).append(';')
            append(formatPrice(line.unitPriceCents)).append(';')
            append(formatPrice(lineHt)).append(';')
            append(formatPrice(lineTva)).append(';')
            append(formatPrice(lineTtc)).append("\r\n")
        }
        append("TOTAL;;")
            .append(totalQty).append(";;")
            .append(formatPrice(totalHt)).append(';')
            .append(formatPrice(totalTva)).append(';')
            .append(formatPrice(totalTtc)).append("\r\n")
    }

    // ---------------- JSON ----------------

    private fun buildJson(channel: Channel): String = buildString {
        append('{')
        append("\"canal\":").append(jsonString(channel.name)).append(',')
        append("\"date\":").append(jsonString(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.FRANCE).format(Date()))).append(',')
        append("\"lignes\":[")
        val sorted = sortLines(channel.lines)
        var totalHt = 0
        sorted.forEachIndexed { idx, line ->
            if (idx > 0) append(',')
            val lineHt = line.unitPriceCents * line.quantity
            totalHt += lineHt
            append('{')
            append("\"categorie\":").append(jsonString(line.categoryName)).append(',')
            append("\"code_barres\":").append(jsonString(line.barcode)).append(',')
            append("\"quantite\":").append(line.quantity).append(',')
            append("\"prix_unitaire_ht_cents\":").append(line.unitPriceCents).append(',')
            append("\"total_ht_cents\":").append(lineHt)
            append('}')
        }
        append(']')
        val totalTva = Math.round(totalHt * VAT_RATE).toInt()
        append(",\"totaux\":{")
        append("\"quantite\":").append(sorted.sumOf { it.quantity }).append(',')
        append("\"ht_cents\":").append(totalHt).append(',')
        append("\"tva_cents\":").append(totalTva).append(',')
        append("\"ttc_cents\":").append(totalHt + totalTva)
        append('}')
        append('}')
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---------------- Sage 50 ----------------
    //
    // Format "Import des pièces paramétrable" :
    //   - 10 colonnes séparées par `;`
    //   - en-tête (ligne 1 à ignorer)
    //   - 1 ligne E : en-tête de pièce (type=Facture, client=003)
    //   - N lignes L : une par article agrégé
    //   - encodage Windows-1252, CRLF, décimale virgule, date JJ/MM/AAAA
    //   - Description = RTF single-line pour conserver gras + saut de ligne
    //     à l'import.
    //   - Les codes articles sont désormais 100% pilotés par l'utilisateur :
    //     renseignés sur la fiche Catégorie (produits scannés) ou dans
    //     les Paramètres (produits manuels). validateSage() garantit qu'ils
    //     sont tous présents avant qu'on arrive ici.

    private fun buildSageCsv(channel: Channel, manualArticleCode: String): String = buildString {
        append("TypeLigne;TypePiece;NumPiece;DatePiece;CodeClient;CodeArticle;Quantite;PUHT;TauxTVA;Description\r\n")

        val today = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
        append("E;Facture;;$today;$SAGE_CLIENT_CODE;;;;;\r\n")

        sortLines(channel.lines).forEach { line ->
            val code = if (line.barcode.isBlank()) {
                manualArticleCode
            } else {
                line.sageArticleCode.orEmpty()
            }
            val qty = line.quantity.toString()
            val puht = formatPrice(line.unitPriceCents)
            val desc = buildSageRtfDescription(line.categoryName, line.barcode)
            append("L;;;;;$code;$qty;$puht;$SAGE_TVA_RATE;$desc\r\n")
        }
    }

    private fun buildSageRtfDescription(categoryName: String, barcode: String): String {
        val label = rtfEscape(categoryName.uppercase().replace(";", ""))
        val ref = rtfEscape(barcode.replace(";", ""))
        return if (ref.isNotBlank()) {
            "{\\rtf1\\ansi\\deff0 $label - \\b $ref\\b0}"
        } else {
            "{\\rtf1\\ansi\\deff0 $label}"
        }
    }

    private fun rtfEscape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("{", "\\{")
        .replace("}", "\\}")

    // ---------------- Helpers communs ----------------

    private fun csvEscape(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            '"' + s.replace("\"", "\"\"") + '"'
        } else s

    private fun formatPrice(cents: Int): String {
        val euros = cents / 100
        val remainder = kotlin.math.abs(cents % 100)
        return "%d,%02d".format(euros, remainder)
    }
}
