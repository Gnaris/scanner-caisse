package com.belijolie.scanner.printer

import com.belijolie.scanner.cart.CartLine
import com.belijolie.scanner.data.AppSettings
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wrapper around DantSu ESC/POS lib tuned for the MUNBYN ITPP047.
 *
 * Layout rules :
 *   - 80 mm paper (printer standard), 48 chars per line.
 *   - 3 chars of padding on BOTH sides of every row and divider → symmetric margins.
 *   - A thin blank line between product rows → aerated table.
 *   - 15 trailing blank lines → the auto-cutter never eats the last text.
 */
object EscPosPrinter {

    private const val DPI = 203
    private const val PAPER_WIDTH_MM = 80f
    private const val CHARS_PER_LINE = 48
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val VAT_RATE = 0.20

    // Side padding applied on both left and right of every content line.
    private const val SIDE_PAD = 3

    // Interior width available for the table.
    private const val INNER_WIDTH = CHARS_PER_LINE - 2 * SIDE_PAD  // = 42

    // Column widths. Sum including 5 separators must equal INNER_WIDTH (42).
    //   Cat 11 + sp + Qt 2 + sp + PU 5 + sp + HT 5 + sp + TVA 5 + sp + TTC 7
    //   = 11+2+5+5+5+7 + 5 = 42 ✓
    private const val COL_CAT = 11
    private const val COL_QT = 2
    private const val COL_PU = 5
    private const val COL_HT = 5
    private const val COL_TVA = 5
    private const val COL_TTC = 7

    private val PAD = " ".repeat(SIDE_PAD)
    private val DIVIDER = PAD + "-".repeat(INNER_WIDTH) + PAD
    private val BLANK_INNER = PAD + " ".repeat(INNER_WIDTH) + PAD

    /**
     * The [EscPosPrinter.printer] field is declared `private final` in the
     * DantSu library, so neither a subclass nor Kotlin's property access can
     * reach it. We fetch it reflectively — the only way to call `feedPaper()`
     * + `cutPaper()` explicitly (DantSu's auto-cut only feeds ~80 dots, not
     * enough to clear a QR section before the cutter).
     */
    private fun EscPosPrinter.commands(): EscPosPrinterCommands {
        val field = EscPosPrinter::class.java.getDeclaredField("printer").apply { isAccessible = true }
        return field.get(this) as EscPosPrinterCommands
    }

    suspend fun printReceipt(
        settings: AppSettings,
        lines: List<CartLine>
    ) = withContext(Dispatchers.IO) {
        val connection = TcpConnection(settings.printerIp, settings.printerPort, SOCKET_TIMEOUT_MS)
        val printer = EscPosPrinter(connection, DPI, PAPER_WIDTH_MM, CHARS_PER_LINE)
        try {
            // Pre-render the double-QR section as a hex-encoded image so DantSu
            // can inline it with the <img> tag.
            val qrSectionHex = if (settings.microstoreQr.isNotBlank() || settings.websiteUrl.isNotBlank()) {
                val bitmap = TicketImage.composeDoubleQrSection(
                    microstoreQr = settings.microstoreQr,
                    microstoreCode = settings.microstoreCode,
                    websiteUrl = settings.websiteUrl
                )
                PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmap)
            } else null

            printer.printFormattedText(buildReceipt(settings, lines, qrSectionHex))
            val cmds = printer.commands()
            cmds.feedPaper(500)
            cmds.cutPaper()
        } finally {
            printer.disconnectPrinter()
        }
    }

    suspend fun printTest(host: String, port: Int) = withContext(Dispatchers.IO) {
        val connection = TcpConnection(host, port, SOCKET_TIMEOUT_MS)
        val printer = EscPosPrinter(connection, DPI, PAPER_WIDTH_MM, CHARS_PER_LINE)
        try {
            printer.printFormattedTextAndCut(
                "[C]<b>TEST IMPRESSION</b>\n" +
                "[C]--------------------------------\n" +
                "[C]Si tu lis ceci, la connexion\n" +
                "[C]Wi-Fi vers l'imprimante marche.\n" +
                blankLines(15)
            )
        } finally {
            printer.disconnectPrinter()
        }
    }

    private fun buildReceipt(
        settings: AppSettings,
        lines: List<CartLine>,
        qrSectionHex: String? = null
    ): String = buildString {
        val now = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.FRANCE).format(Date())

        // ---------- Header ----------
        append("[C]<b><font size='big'>").append(escape(settings.shopName)).append("</font></b>\n\n")
        if (settings.address.isNotBlank()) {
            append("[C]").append(escape(settings.address)).append('\n')
        }
        if (settings.addressComplement.isNotBlank()) {
            append("[C]").append(escape(settings.addressComplement)).append('\n')
        }
        val cityLine = listOf(settings.postalCode, settings.city)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (cityLine.isNotBlank()) {
            append("[C]").append(escape(cityLine)).append('\n')
        }
        if (settings.country.isNotBlank()) {
            append("[C]").append(escape(settings.country)).append('\n')
        }

        append("[L]").append(DIVIDER).append('\n')
        append("[C]").append(now).append('\n')
        append("[L]").append(DIVIDER).append('\n')

        // ---------- Table header ----------
        append("[L]").append(tableRow("Cat", "Qt", "P.U.", "HT", "TVA", "TTC")).append('\n')
        append("[L]").append(DIVIDER).append('\n')

        // ---------- Product rows ----------
        var totalQty = 0
        var totalTtcCents = 0
        var totalHtCents = 0

        var currentCategory: String? = null
        var categoryIndex = -1
        lines.forEach { line ->
            if (line.categoryName != currentCategory) {
                currentCategory = line.categoryName
                categoryIndex++
            }
            // Prices stored on the product are HT.
            val lineHt = line.unitPriceCents * line.quantity
            val lineTva = calcTvaCents(lineHt)
            val lineTtc = lineHt + lineTva

            totalQty += line.quantity
            totalHtCents += lineHt
            totalTtcCents += lineTtc

            val bold = categoryIndex % 2 == 1

            val catChunks = line.categoryName.chunked(COL_CAT).ifEmpty { listOf("") }
            catChunks.forEachIndexed { idx, chunk ->
                val row = if (idx == 0) {
                    tableRow(
                        chunk,
                        line.quantity.toString(),
                        formatPrice(line.unitPriceCents),
                        formatPrice(lineHt),
                        formatPrice(lineTva),
                        formatPrice(lineTtc)
                    )
                } else {
                    tableRow(chunk, "", "", "", "", "")
                }
                if (bold) append("[L]<b>").append(row).append("</b>\n")
                else append("[L]").append(row).append('\n')
            }
            // No spacer between rows — keep the table tight.
        }

        append("[L]").append(DIVIDER).append('\n')

        // ---------- Totals (tight, no extra blank) ----------
        val totalTva = totalTtcCents - totalHtCents
        val totalHt = totalHtCents

        append("[L]").append(PAD).append("Quantité totale[R]")
            .append(totalQty.toString()).append(PAD).append('\n')
        append("[L]").append(PAD).append("Total HT[R]")
            .append(formatPrice(totalHt)).append(" EUR").append(PAD).append('\n')
        append("[L]").append(PAD).append("TVA totale (20%)[R]")
            .append(formatPrice(totalTva)).append(" EUR").append(PAD).append('\n')
        append("[L]").append(PAD).append("<b>Total TTC[R]")
            .append(formatPrice(totalTtcCents)).append(" EUR</b>").append(PAD).append('\n')

        // ---------- Microstore + Boutique en ligne (QR codes side by side) ----------
        if (qrSectionHex != null) {
            append("[L]").append(DIVIDER).append('\n')
            // [L] alignment on the <img> tag anchors the image to the LEFT of
            // the paper rather than centering it — matches the user's request
            // to shift the QR block visually to the left.
            append("[L]<img>").append(qrSectionHex).append("</img>\n")
        }

        // Minimal padding — the explicit feedPaper(500) handles the cut cushion.
        append(blankLines(3))
    }

    private fun tableRow(cat: String, qt: String, pu: String, ht: String, tva: String, ttc: String): String {
        val catCol = cat.take(COL_CAT).padEnd(COL_CAT)
        val qtCol = qt.padStart(COL_QT)
        val puCol = pu.padStart(COL_PU)
        val htCol = ht.padStart(COL_HT)
        val tvaCol = tva.padStart(COL_TVA)
        val ttcCol = ttc.padStart(COL_TTC)
        return "$PAD$catCol $qtCol $puCol $htCol $tvaCol $ttcCol$PAD"
    }

    private fun blankLines(n: Int): String = "\n".repeat(n)

    private fun calcTvaCents(htCents: Int): Int = Math.round(htCents * VAT_RATE).toInt()

    private fun formatPrice(cents: Int): String {
        val euros = cents / 100
        val remainder = cents % 100
        return "%d.%02d".format(euros, remainder)
    }

    private fun escape(text: String): String =
        text.replace("[", "(").replace("]", ")").replace("<", "(").replace(">", ")")
}
