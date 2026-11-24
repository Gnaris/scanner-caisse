package com.belijolie.scanner.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.belijolie.scanner.cart.CartLine
import com.belijolie.scanner.data.AppSettings
import java.io.ByteArrayOutputStream

/**
 * Ticket de caisse au format PDF, identique au ticket imprimé : on réutilise
 * le texte formaté d'EscPosPrinter.buildReceipt (balises [L] [C] [R], <b>,
 * <font size='big'>, <img>) et les mêmes bitmaps QR, puis on les dessine sur
 * une page PDF de la largeur d'un ticket 80 mm (hauteur ajustée au contenu).
 *
 * Unités de dessin = points d'imprimante (576 de large, 48 caractères de
 * 12 points), mis à l'échelle de la zone imprimable 72 mm du papier 80 mm.
 */
object TicketPdf {

    private const val WIDTH_DOTS = 576f
    private const val CHARS = 48
    private const val CHAR_W = WIDTH_DOTS / CHARS
    private const val LINE_H = 26f
    private const val BIG_LINE_H = 52f

    // 1 mm = 72 / 25,4 points PDF
    private const val PT_PER_MM = 72f / 25.4f
    private const val PAGE_W_PT = 80f * PT_PER_MM
    private const val MARGIN_X_PT = 4f * PT_PER_MM
    private const val MARGIN_Y_PT = 6f * PT_PER_MM
    private const val SCALE = (PAGE_W_PT - 2 * MARGIN_X_PT) / WIDTH_DOTS

    private sealed class Item {
        data class Text(val segments: List<Seg>, val big: Boolean) : Item()
        data class Image(val bitmap: Bitmap) : Item()
        data object Blank : Item()
    }

    private data class Seg(val align: Char, val runs: List<Pair<String, Boolean>>)

    fun render(
        settings: AppSettings,
        lines: List<CartLine>,
        lang: TicketLang,
        discountPercent: Int,
    ): ByteArray {
        val bitmaps = EscPosPrinter.qrSectionBitmaps(settings, lang)
        // Les images sont repérées par leur index dans le texte formaté
        val text = EscPosPrinter.buildReceipt(
            settings, lines, lang, bitmaps.indices.map { it.toString() }, discountPercent,
        )
        val items = parse(text.trimEnd('\n'), bitmaps)

        val contentH = items.sumOf { item ->
            when (item) {
                is Item.Text -> (if (item.big) BIG_LINE_H else LINE_H).toDouble()
                is Item.Image -> item.bitmap.height.toDouble() + 8
                Item.Blank -> LINE_H.toDouble()
            }
        }.toFloat()
        val pageH = (contentH * SCALE + 2 * MARGIN_Y_PT).toInt() + 1

        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W_PT.toInt() + 1, pageH, 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            c.translate(MARGIN_X_PT, MARGIN_Y_PT)
            c.scale(SCALE, SCALE)
            var y = 0f
            for (item in items) {
                when (item) {
                    is Item.Text -> {
                        val h = if (item.big) BIG_LINE_H else LINE_H
                        drawLine(c, item, y + h * 0.78f)
                        y += h
                    }
                    is Item.Image -> {
                        val x = (WIDTH_DOTS - item.bitmap.width) / 2f
                        c.drawBitmap(item.bitmap, x, y + 4, null)
                        y += item.bitmap.height + 8
                    }
                    Item.Blank -> y += LINE_H
                }
            }
            doc.finishPage(page)
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun parse(text: String, bitmaps: List<Bitmap>): List<Item> {
        val items = mutableListOf<Item>()
        var bold = false
        val imgRe = Regex("""<img>(\d+)</img>""")
        for (raw in text.split('\n')) {
            val img = imgRe.find(raw)
            if (img != null) {
                bitmaps.getOrNull(img.groupValues[1].toInt())?.let { items += Item.Image(it) }
                continue
            }
            if (raw.isBlank()) { items += Item.Blank; continue }
            val big = raw.contains("<font size='big'>")
            val line = raw.replace(Regex("</?font[^>]*>"), "")
            // Découpe en segments [L] / [C] / [R]
            val segs = mutableListOf<Seg>()
            val parts = Regex("""\[([LCR])]""").split(line)
            val aligns = Regex("""\[([LCR])]""").findAll(line).map { it.groupValues[1][0] }.toList()
            // parts[0] = texte avant la 1re balise (normalement vide)
            for ((i, part) in parts.drop(1).withIndex()) {
                val runs = mutableListOf<Pair<String, Boolean>>()
                var rest = part
                while (rest.isNotEmpty()) {
                    val next = Regex("</?b>").find(rest)
                    if (next == null) { runs += rest to bold; break }
                    if (next.range.first > 0) runs += rest.substring(0, next.range.first) to bold
                    bold = next.value == "<b>"
                    rest = rest.substring(next.range.last + 1)
                }
                segs += Seg(aligns.getOrElse(i) { 'L' }, runs)
            }
            items += Item.Text(segs, big)
        }
        return items
    }

    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun drawLine(c: Canvas, item: Item.Text, baseline: Float) {
        val cw = if (item.big) CHAR_W * 2 else CHAR_W
        // Taille de police telle qu'un caractère occupe exactement une colonne
        val size = cw / regular.apply { textSize = 100f }.measureText("M") * 100f
        regular.textSize = size
        boldPaint.textSize = size
        for (seg in item.segments) {
            val len = seg.runs.sumOf { it.first.length }
            var x = when (seg.align) {
                'C' -> (WIDTH_DOTS - len * cw) / 2f
                'R' -> WIDTH_DOTS - len * cw
                else -> 0f
            }
            for ((txt, b) in seg.runs) {
                val p = if (b) boldPaint else regular
                // Caractère par caractère : colonnes parfaitement alignées
                for (ch in txt) {
                    c.drawText(ch.toString(), x, baseline, p)
                    x += cw
                }
            }
        }
    }
}
