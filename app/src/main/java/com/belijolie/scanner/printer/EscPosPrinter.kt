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

    // Column widths. Sum including 3 separators must equal INNER_WIDTH (42).
    //   Cat 18 + sp + Qt 4 + sp + PU 8 + sp + HT 9  = 18+4+8+9 + 3 = 42 ✓
    // TVA et TTC sont calculés en bas de ticket (lignes totaux) mais plus
    // affichés par ligne pour aérer et élargir la colonne catégorie.
    private const val COL_CAT = 18
    private const val COL_QT = 4
    private const val COL_PU = 8
    private const val COL_HT = 9

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
        lines: List<CartLine>,
        lang: TicketLang = TicketLang.FR
    ) = withContext(Dispatchers.IO) {
        val connection = TcpConnection(settings.printerIp, settings.printerPort, SOCKET_TIMEOUT_MS)
        val printer = EscPosPrinter(connection, DPI, PAPER_WIDTH_MM, CHARS_PER_LINE)
        try {
            // Layout dynamique des QR codes selon ce qui est configuré :
            // 0 → aucun bitmap ; 1 → single centré ; 2 → double côte à côte ;
            // 3 → double (Microstore + Boutique) puis Avis Google en style
            // "appel à l'action" en dessous.
            val labels = TicketLabels.of(lang)
            val microstore = settings.microstoreQr.takeIf { it.isNotBlank() }?.let {
                TicketImage.QrEntry(
                    title = labels.microstore,
                    content = it,
                    footer = settings.microstoreCode
                )
            }
            val boutique = settings.websiteUrl.takeIf { it.isNotBlank() }?.let {
                TicketImage.QrEntry(
                    title = labels.onlineShop,
                    content = it,
                    footer = TicketImage.truncateForWidth(it, 24)
                )
            }
            val avis = settings.googleReviewUrl.takeIf { it.isNotBlank() }

            val qrBitmapsHex = mutableListOf<String>()
            val entries = listOfNotNull(microstore, boutique)
            when {
                // Les 3 sont présents → double + section avis dédiée (style gros)
                entries.size == 2 && avis != null -> {
                    qrBitmapsHex += PrinterTextParserImg.bitmapToHexadecimalString(
                        printer,
                        TicketImage.composeDoubleQrSection(entries[0], entries[1])
                    )
                    qrBitmapsHex += PrinterTextParserImg.bitmapToHexadecimalString(
                        printer,
                        TicketImage.composeReviewQrSection(avis, lang)
                    )
                }
                // 2 QR dont un est Avis → double avec style petit uniforme
                entries.size == 1 && avis != null -> {
                    val avisEntry = TicketImage.QrEntry(labels.googleReview, avis)
                    qrBitmapsHex += PrinterTextParserImg.bitmapToHexadecimalString(
                        printer,
                        TicketImage.composeDoubleQrSection(entries[0], avisEntry)
                    )
                }
                // 2 QR sans Avis → double standard
                entries.size == 2 -> {
                    qrBitmapsHex += PrinterTextParserImg.bitmapToHexadecimalString(
                        printer,
                        TicketImage.composeDoubleQrSection(entries[0], entries[1])
                    )
                }
                // 1 QR seul parmi Microstore / Boutique → centré style petit
                entries.size == 1 -> {
                    qrBitmapsHex += PrinterTextParserImg.bitmapToHexadecimalString(
                        printer,
                        TicketImage.composeSingleQrSection(entries[0])
                    )
                }
                // 1 QR = Avis Google seul → section d'appel à l'action centrée
                entries.isEmpty() && avis != null -> {
                    qrBitmapsHex += PrinterTextParserImg.bitmapToHexadecimalString(
                        printer,
                        TicketImage.composeReviewQrSection(avis, lang)
                    )
                }
                // 0 QR → rien
            }

            printer.printFormattedText(buildReceipt(settings, lines, lang, qrBitmapsHex))
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
        lang: TicketLang,
        qrBitmapsHex: List<String> = emptyList()
    ): String = buildString {
        val labels = TicketLabels.of(lang)
        val dateLocale = if (lang == TicketLang.EN) Locale.ENGLISH else Locale.FRANCE
        val datePattern = if (lang == TicketLang.EN) "MM/dd/yyyy  HH:mm" else "dd/MM/yyyy  HH:mm"
        val now = SimpleDateFormat(datePattern, dateLocale).format(Date())

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
        append("[L]").append(tableRow(labels.headerCategory, labels.headerQty, labels.headerUnit, labels.headerNet)).append('\n')
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

            val displayName = resolveLineDisplayName(
                categoryName = line.categoryName,
                categoryNameEn = line.categoryNameEn,
                nameEn = line.nameEn,
                lang = lang
            )
            val catChunks = displayName.chunked(COL_CAT).ifEmpty { listOf("") }
            catChunks.forEachIndexed { idx, chunk ->
                val row = if (idx == 0) {
                    tableRow(
                        chunk,
                        "x" + line.quantity,
                        formatPrice(line.unitPriceCents) + "€",
                        formatPrice(lineHt) + "€"
                    )
                } else {
                    tableRow(chunk, "", "", "")
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

        append("[L]").append(PAD).append(labels.totalQuantity).append("[R]")
            .append("x").append(totalQty.toString()).append(PAD).append('\n')
        append("[L]").append(PAD).append(labels.totalNet).append("[R]")
            .append(formatPrice(totalHt)).append(" €").append(PAD).append('\n')
        append("[L]").append(PAD).append(labels.totalVat).append("[R]")
            .append(formatPrice(totalTva)).append(" €").append(PAD).append('\n')
        append("[L]").append(PAD).append("<b>").append(labels.totalGross).append("[R]")
            .append(formatPrice(totalTtcCents)).append(" €</b>").append(PAD).append('\n')

        // ---------- Sections QR (dynamiques : 0, 1 ou 2 bitmaps) ----------
        // [C]<img> : l'imprimante centre l'image sur la zone imprimable.
        // Chaque bitmap TicketImage fait exactement 576 px (largeur imprimable
        // du MUNBYN) → les QR tombent pile centrés sur le papier.
        qrBitmapsHex.forEach { hex ->
            append("[L]").append(DIVIDER).append('\n')
            append("[C]<img>").append(hex).append("</img>\n")
        }

        // Minimal padding — the explicit feedPaper(500) handles the cut cushion.
        append(blankLines(3))
    }

    private fun tableRow(cat: String, qt: String, pu: String, ht: String): String {
        val catCol = cat.take(COL_CAT).padEnd(COL_CAT)
        val qtCol = qt.padStart(COL_QT)
        val puCol = pu.padStart(COL_PU)
        val htCol = ht.padStart(COL_HT)
        return "$PAD$catCol $qtCol $puCol $htCol$PAD"
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
