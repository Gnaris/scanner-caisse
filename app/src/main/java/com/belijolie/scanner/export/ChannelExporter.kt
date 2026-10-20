package com.belijolie.scanner.export

import com.belijolie.scanner.cart.CartLine
import com.belijolie.scanner.cart.Channel
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.ui.checkout.sortLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
 * Résultat d'un export réussi. Le ViewModel l'utilise pour afficher le bon
 * message selon le format (nom de fichier pour CSV/JSON, numéro de facture
 * pour SAGE qui a été créé directement dans Sage 50 via SageBridge).
 */
sealed class ExportSuccess {
    data class File(val filename: String) : ExportSuccess()
    data class SageInvoice(val numero: String) : ExportSuccess()
}

/**
 * Envoie le contenu d'un canal vers l'écoute HTTP du PC. Trois formats :
 *  - [ExportFormat.CSV] : CSV UTF-8 avec BOM, ouverture directe dans Excel.
 *  - [ExportFormat.JSON] : JSON brut (produits + totaux), pour intégration.
 *  - [ExportFormat.SAGE] : crée directement une facture provisoire dans
 *      Sage 50 via SageBridge. Le PDA envoie du JSON SageBridge au pc-listener
 *      sur le PC Sage ; pc-listener relaie à `http://127.0.0.1:8765/facture`
 *      (SageBridge). SageBridge résout le client (003 par défaut), crée la
 *      facture par COM et renvoie son numéro.
 */
object ChannelExporter {

    private const val TIMEOUT_MS = 10_000
    // SageBridge peut mettre un moment pour résoudre le client + créer la pièce
    // par COM. Côté SageBridge chaque opération est bornée à 2 min ; on reste
    // en dessous (30 s suffisent largement en pratique).
    private const val SAGE_TIMEOUT_MS = 30_000
    private const val VAT_RATE = 0.20
    private const val SAGE_CLIENT_CODE = "003"
    private const val SAGE_VAT_RATE = 20.0
    private const val SAGE_SITE = "scanner-caisse"

    suspend fun exportChannel(
        settings: AppSettings,
        channel: Channel,
        format: ExportFormat
    ): Result<ExportSuccess> = withContext(Dispatchers.IO) {
        if (settings.exportUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Configure l'URL d'export dans les paramètres")
            )
        }

        if (format == ExportFormat.SAGE) {
            val validationError = validateSage(channel.lines, settings.sageManualArticleCode.trim())
            if (validationError != null) {
                return@withContext Result.failure(SageExportValidationException(validationError))
            }
            return@withContext runCatching {
                postSageInvoice(settings, channel)
            }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
        val safeName = channel.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "canal" }
        val prefix = when (format) {
            ExportFormat.CSV -> "canal"
            ExportFormat.JSON -> "canal-json"
            ExportFormat.SAGE -> "sage" // unreachable
        }
        val extension = when (format) {
            ExportFormat.JSON -> "json"
            else -> "csv"
        }
        val filename = "$prefix-$safeName-$timestamp.$extension"
        val (payload, contentType) = when (format) {
            ExportFormat.CSV -> buildCsv(channel).toByteArray(Charsets.UTF_8) to "text/csv; charset=utf-8"
            ExportFormat.JSON -> buildJson(channel).toByteArray(Charsets.UTF_8) to "application/json; charset=utf-8"
            ExportFormat.SAGE -> error("SAGE handled above")
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
                ExportSuccess.File(filename)
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
     *  - Toute ligne manuelle (barcode vide) SANS sageArticleCode nécessite
     *    [manualArticleCode] non vide dans les paramètres. Si la ligne manuelle
     *    porte déjà un sageArticleCode (= nom tapé qui matche une catégorie
     *    existante), elle utilise ce code et n'exige pas le fallback.
     *  - Toute ligne scannée (barcode non vide) nécessite [CartLine.sageArticleCode]
     *    non vide (= sa catégorie a été configurée en DB).
     */
    private fun validateSage(lines: List<CartLine>, manualArticleCode: String): String? {
        val hasManualLineWithoutCode = lines.any {
            it.barcode.isBlank() && it.sageArticleCode.isNullOrBlank()
        }
        val categoriesMissingCode = lines
            .filter { it.barcode.isNotBlank() && it.sageArticleCode.isNullOrBlank() }
            .map { it.categoryName }
            .distinct()

        val errors = mutableListOf<String>()
        if (hasManualLineWithoutCode && manualArticleCode.isBlank()) {
            errors += "• Des produits ont été ajoutés manuellement au panier, mais aucun " +
                "« Code article Sage manuel » n'est renseigné dans les paramètres."
        }
        if (categoriesMissingCode.isNotEmpty()) {
            errors += "• Les catégories suivantes n'ont pas de code article Sage : " +
                categoriesMissingCode.joinToString(", ") { "« $it »" } + "."
        }

        return if (errors.isEmpty()) null else buildString {
            appendLine("Impossible de créer la facture Sage 50 :")
            appendLine()
            errors.forEach { appendLine(it) }
            appendLine()
            append("Renseigne les codes articles manquants puis relance.")
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

    // ---------------- Sage 50 (via SageBridge) ----------------
    //
    // Le PDA Android ne peut pas joindre SageBridge directement : SageBridge
    // écoute en loopback (127.0.0.1:8765). On passe donc par pc-listener (déjà
    // exposé sur le LAN) qui expose POST /sage-facture et relaie à SageBridge.
    //
    // Format d'un payload SageBridge (voir SageBridge CLAUDE.md, section
    // « API HTTP locale ») :
    //   { site, orderNo, client, remarque, force, simulation,
    //     lines: [ { code, qty, puht, tva, description } ] }
    //
    // Décisions produit :
    //  - Pas d'adresse client envoyée : on utilise toujours le code 003 (client
    //    par défaut Beli & Jolie dans Sage 50). Pas d'ambiguïté, SageBridge
    //    ne crée aucun client.
    //  - orderNo = channel.id → anti-doublon SageBridge (si on reclique l'export
    //    sur le même panier, HTTP 409). Pour recréer la facture volontairement
    //    il faudra côté SageBridge que `force=true` soit passé — ici on garde
    //    `force=false` par défaut (plus sûr).
    //  - Description = nom catégorie en MAJUSCULES. Pas de code-barres (demande
    //    produit : le barcode ne doit pas apparaître sur la facture Sage).

    private fun postSageInvoice(settings: AppSettings, channel: Channel): ExportSuccess.SageInvoice {
        val body = buildSageInvoiceJson(channel, settings.sageManualArticleCode.trim())
        val url = URL(sageInvoiceUrl(settings.exportUrl))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = SAGE_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = runCatching { JSONObject(response) }.getOrNull()
            val sageError = parsed?.optString("error")?.takeIf { it.isNotBlank() }

            if (code == 409) {
                val numero = parsed?.optString("numero")?.takeIf { it.isNotBlank() }
                throw IOException(
                    "Cette facture a déjà été créée dans Sage" +
                        (numero?.let { " (n° $it)" } ?: "") +
                        ". Modifie le panier ou renomme le canal pour recréer."
                )
            }
            if (code !in 200..299) {
                throw IOException(sageError ?: "SageBridge a répondu HTTP $code")
            }
            if (parsed == null) {
                throw IOException("Réponse SageBridge illisible")
            }
            val ok = parsed.optBoolean("ok", false)
            if (!ok) {
                throw IOException(sageError ?: "SageBridge a refusé la facture")
            }
            val numero = parsed.optString("numero").ifBlank { "?" }
            return ExportSuccess.SageInvoice(numero)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Construit l'URL `/sage-facture` sur le pc-listener à partir de l'URL
     * d'export configurée. On garde le même host:port (= pc-listener) mais on
     * remplace le chemin, parce que `exportUrl` pointe aujourd'hui vers
     * `/export` (dépose de fichier).
     */
    private fun sageInvoiceUrl(exportUrl: String): String {
        val base = try {
            val u = URL(exportUrl)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.protocol}://${u.host}$port"
        } catch (_: Exception) {
            // Fallback : si l'URL ne parse pas on la laisse telle quelle,
            // la requête échouera avec un message réseau clair.
            exportUrl.trimEnd('/')
        }
        return "$base/sage-facture"
    }

    private fun buildSageInvoiceJson(channel: Channel, manualArticleCode: String): String {
        val root = JSONObject()
        root.put("site", SAGE_SITE)
        root.put("orderNo", channel.id)
        root.put("client", SAGE_CLIENT_CODE)
        root.put("remarque", channel.name)
        root.put("force", false)
        root.put("simulation", false)

        val lines = JSONArray()
        sortLines(channel.lines).forEach { line ->
            val code = line.sageArticleCode?.takeIf { it.isNotBlank() }
                ?: if (line.barcode.isBlank()) manualArticleCode else ""
            val ligne = JSONObject()
            ligne.put("code", code)
            ligne.put("qty", line.quantity)
            ligne.put("puht", line.unitPriceCents / 100.0)
            ligne.put("tva", SAGE_VAT_RATE)
            ligne.put("description", buildSageDescription(line.categoryName))
            lines.put(ligne)
        }
        root.put("lines", lines)
        return root.toString()
    }

    /**
     * Description envoyée à SageBridge en texte brut (le COM Sage stocke tel
     * quel). Toujours le nom de catégorie en majuscules, sans code-barres.
     */
    private fun buildSageDescription(categoryName: String): String =
        categoryName.uppercase().trim()

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
