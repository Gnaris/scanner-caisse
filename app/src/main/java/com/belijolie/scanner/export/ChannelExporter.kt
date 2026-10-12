package com.belijolie.scanner.export

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

    private fun buildSageCsv(channel: Channel, manualArticleCode: String): String = buildString {
        append("TypeLigne;TypePiece;NumPiece;DatePiece;CodeClient;CodeArticle;Quantite;PUHT;TauxTVA;Description\r\n")

        val today = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
        append("E;Facture;;$today;$SAGE_CLIENT_CODE;;;;;\r\n")

        sortLines(channel.lines).forEach { line ->
            // Ligne sans code-barre = ajoutée manuellement dans le canal → on
            // utilise le code article Sage paramétré (ex. ART_MANUEL ou code
            // générique défini dans Sage). Sinon mapping par catégorie classique.
            val code = if (line.barcode.isBlank() && manualArticleCode.isNotBlank()) {
                manualArticleCode
            } else {
                sageArticleFor(line.categoryName)
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

    /**
     * Mappe une catégorie libre vers le code article Sage 50 attendu par le
     * paramétrage du client. Codes basés sur le dossier Sage de Beli & Jolie
     * (ART0001-ART0019). Catégorie inconnue → code vide → Sage demandera à
     * l'utilisateur de compléter dans la pièce avant validation.
     */
    private fun sageArticleFor(categoryName: String): String {
        val c = categoryName.lowercase().trim()
        return when {
            c.contains("présentoir") || c.contains("presentoir") || c.contains("support") -> "ART0013"
            c.contains("lot") || c.startsWith("lots ") -> when {
                c.contains("boucle") -> "ART0009"
                c.contains("bague") && c.contains("bracelet") -> "ART0015"
                c.contains("bague") -> "ART0010"
                c.contains("bracelet") -> "ART0011"
                c.contains("collier") -> "ART0007"
                c.contains("pendentif") -> "ART0008"
                else -> "ART0012"
            }
            c.contains("boucle") -> "ART0003"
            c.contains("jonc") -> "ART0016"
            c.contains("bracelet") -> "ART0002"
            c.contains("bague") -> "ART0004"
            c.contains("collier") -> "ART0001"
            c.contains("pendentif") -> "ART0014"
            c.contains("chaîne") && c.contains("cheville") -> "ART0006"
            c.contains("chaine") && c.contains("cheville") -> "ART0006"
            c.contains("parure") -> "ART0005"
            c.contains("broche") -> "ART0018"
            c.contains("piercing") -> "ART0019"
            c.contains("sac") || c.contains("pochon") -> "ART0017"
            else -> ""
        }
    }

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
