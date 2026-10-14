package com.belijolie.scanner.sync

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Encode / décode un [CatalogSnapshot] en une ou plusieurs chaînes QR.
 *
 * Format d'un segment :
 * ```
 * BJQR:1:<EXPORT_ID>:<N>/<T>:<PAYLOAD_BASE64>
 * ```
 *  - `BJQR` : préfixe identifiant l'app, pour filtrer les QR étrangers.
 *  - `1` : version du protocole transport (changement = incompatibilité).
 *  - `EXPORT_ID` : 6 chars alphanum générés aléatoirement, identifient CE
 *    export. Si l'appareil destination voit un segment avec un ID différent
 *    pendant qu'il est en train de collecter, il reset et redémarre.
 *  - `N/T` : numéro du segment / total.
 *  - `PAYLOAD_BASE64` : morceau du JSON gzippé encodé en base64 URL-safe.
 *
 * La taille cible par segment est [CHUNK_SIZE] chars de payload, choisi pour
 * tenir dans un QR version ~20 correction M (fiable sur petit écran).
 */
object QrCodec {

    private const val PROTOCOL_VERSION = 1
    private const val PREFIX = "BJQR"
    private const val CHUNK_SIZE = 900
    private const val EXPORT_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val EXPORT_ID_LENGTH = 6

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Encode un snapshot en segments QR, chaque segment prêt à être affiché. */
    fun encodeToSegments(snapshot: CatalogSnapshot): List<String> {
        val payload = json.encodeToString(snapshot)
        val gzipped = ByteArrayOutputStream().use { bout ->
            GZIPOutputStream(bout).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            bout.toByteArray()
        }
        val b64 = Base64.encodeToString(gzipped, Base64.NO_WRAP or Base64.URL_SAFE)
        val chunks = b64.chunked(CHUNK_SIZE)
        val exportId = randomExportId()
        return chunks.mapIndexed { i, chunk ->
            "$PREFIX:$PROTOCOL_VERSION:$exportId:${i + 1}/${chunks.size}:$chunk"
        }
    }

    private fun randomExportId(): String =
        (1..EXPORT_ID_LENGTH).map { EXPORT_ID_CHARS.random() }.joinToString("")

    /**
     * Parse un segment reçu. Renvoie `null` si le QR scanné n'est pas un
     * segment valide (QR étranger, autre app, format cassé).
     */
    fun parseSegment(raw: String): Segment? {
        val regex = Regex("^$PREFIX:$PROTOCOL_VERSION:([A-Z0-9]{$EXPORT_ID_LENGTH}):(\\d+)/(\\d+):(.*)$")
        val match = regex.matchEntire(raw) ?: return null
        val (exportId, nStr, tStr, payload) = match.destructured
        val n = nStr.toIntOrNull() ?: return null
        val t = tStr.toIntOrNull() ?: return null
        if (n < 1 || t < 1 || n > t) return null
        if (payload.isEmpty()) return null
        return Segment(exportId, n, t, payload)
    }

    /** Reconstitue le snapshot depuis la liste complète de segments. */
    fun decodeFromSegments(segments: List<Segment>): CatalogSnapshot {
        require(segments.isNotEmpty()) { "Aucun segment à décoder" }
        val total = segments.first().total
        require(segments.size == total) { "Il manque des segments" }
        val payload = segments.sortedBy { it.index }.joinToString("") { it.payload }
        val gzipped = Base64.decode(payload, Base64.NO_WRAP or Base64.URL_SAFE)
        val raw = GZIPInputStream(gzipped.inputStream()).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        return json.decodeFromString(CatalogSnapshot.serializer(), raw)
    }

    data class Segment(
        val exportId: String,
        val index: Int,
        val total: Int,
        val payload: String,
    )
}

/**
 * Accumule les segments QR scannés en continu, détecte les doublons et les
 * segments d'un AUTRE export (reset auto dans ce cas).
 */
class SegmentCollector {
    private var currentExportId: String? = null
    private var expectedTotal: Int = 0
    private val received = mutableMapOf<Int, QrCodec.Segment>()

    val progress: Pair<Int, Int> get() = received.size to expectedTotal

    val isComplete: Boolean get() = expectedTotal > 0 && received.size == expectedTotal

    fun receive(raw: String): Outcome {
        val seg = QrCodec.parseSegment(raw) ?: return Outcome.ForeignQr
        val current = currentExportId
        if (current != null && current != seg.exportId) {
            // Autre export → on efface tout et on redémarre sur le nouveau.
            received.clear()
            currentExportId = seg.exportId
            expectedTotal = seg.total
            received[seg.index] = seg
            return Outcome.NewExportStarted(seg.index, seg.total)
        }
        if (current == null) {
            currentExportId = seg.exportId
            expectedTotal = seg.total
        }
        if (received.containsKey(seg.index)) return Outcome.Duplicate(seg.index)
        received[seg.index] = seg
        return Outcome.Accepted(seg.index, seg.total)
    }

    fun collectedSegments(): List<QrCodec.Segment> = received.values.toList()

    fun reset() {
        currentExportId = null
        expectedTotal = 0
        received.clear()
    }

    sealed interface Outcome {
        data class Accepted(val index: Int, val total: Int) : Outcome
        data class Duplicate(val index: Int) : Outcome
        data class NewExportStarted(val index: Int, val total: Int) : Outcome
        data object ForeignQr : Outcome
    }
}
