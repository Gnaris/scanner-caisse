package com.belijolie.scanner.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Rendu des sections QR en bas de ticket.
 *
 * Trois sources possibles (toutes optionnelles) : Microstore, Boutique en
 * ligne, Avis Google. Le layout s'adapte au nombre configuré :
 *   - 0 QR → rien
 *   - 1 QR → centré sur le papier
 *   - 2 QR → côte à côte
 *   - 3 QR → Microstore + Boutique côte à côte, puis Avis Google en bas
 *     (centré, format "appel à l'action" avec titre + subtitle)
 */
object TicketImage {

    /**
     * Largeur du bitmap = largeur imprimable du MUNBYN ITPP047 : 72 mm × 8 dots/mm
     * = 576 dots. En conjonction avec `[C]<img>` dans EscPosPrinter, l'image est
     * centrée pile sur la bande imprimée.
     */
    private const val WIDTH_PX = 576

    private const val QR_PX = 180
    private const val PADDING = 8
    private const val TITLE_HEIGHT = 32
    private const val FOOTER_HEIGHT = 42

    /** Une entrée QR : titre affiché au-dessus, contenu encodé, footer court. */
    data class QrEntry(
        val title: String,
        val content: String,
        val footer: String = ""
    )

    /**
     * Compose une section QR unique centrée sur toute la largeur du papier.
     * Même style visuel que les colonnes du double QR, mais seul au milieu.
     */
    fun composeSingleQrSection(entry: QrEntry): Bitmap {
        val height = TITLE_HEIGHT + PADDING + QR_PX + PADDING + FOOTER_HEIGHT + PADDING

        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = titlePaint()
        val footerPaint = footerPaint()

        drawColumn(
            canvas = canvas,
            centerX = WIDTH_PX / 2,
            qr = generateQrBitmap(entry.content, QR_PX),
            title = entry.title,
            footer = entry.footer,
            titlePaint = titlePaint,
            footerPaint = footerPaint
        )

        return bitmap
    }

    /**
     * Compose une section avec deux QR codes côte à côte (gauche + droite).
     * Chaque QR est centré dans sa demi-bande du papier.
     */
    fun composeDoubleQrSection(left: QrEntry, right: QrEntry): Bitmap {
        val height = TITLE_HEIGHT + PADDING + QR_PX + PADDING + FOOTER_HEIGHT + PADDING

        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = titlePaint()
        val footerPaint = footerPaint()

        drawColumn(
            canvas = canvas,
            centerX = WIDTH_PX / 4,
            qr = generateQrBitmap(left.content, QR_PX),
            title = left.title,
            footer = left.footer,
            titlePaint = titlePaint,
            footerPaint = footerPaint
        )
        drawColumn(
            canvas = canvas,
            centerX = WIDTH_PX * 3 / 4,
            qr = generateQrBitmap(right.content, QR_PX),
            title = right.title,
            footer = right.footer,
            titlePaint = titlePaint,
            footerPaint = footerPaint
        )

        return bitmap
    }

    /**
     * Section "Avis Google" en style "appel à l'action" :
     * titre très gros, QR plus grand, subtitle en-dessous.
     * Utilisée uniquement quand les 3 QR sont configurés (Avis Google tout
     * seul en bas, après le double Microstore + Boutique).
     */
    fun composeReviewQrSection(url: String, lang: TicketLang = TicketLang.FR): Bitmap {
        val labels = TicketLabels.of(lang)
        val qrSize = 220
        val titleHeight = 44
        val subtitleHeight = 42
        // +10px de marge en bas pour éviter que le subtitle en gras touche
        // le bord inférieur et soit visuellement coupé.
        val bottomMargin = 10
        val height = titleHeight + PADDING + qrSize + PADDING + subtitleHeight + PADDING + bottomMargin

        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            textAlign = Paint.Align.CENTER
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            textAlign = Paint.Align.CENTER
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val centerX = WIDTH_PX / 2
        canvas.drawText(labels.reviewTitle, centerX.toFloat(), (titleHeight - 10).toFloat(), titlePaint)

        val qr = generateQrBitmap(url, qrSize)
        val qrLeft = centerX - qrSize / 2
        val qrTop = titleHeight + PADDING
        canvas.drawBitmap(qr, qrLeft.toFloat(), qrTop.toFloat(), null)

        canvas.drawText(
            labels.reviewSubtitle,
            centerX.toFloat(),
            (qrTop + qrSize + PADDING + subtitleHeight - 10).toFloat(),
            subtitlePaint
        )

        return bitmap
    }

    private fun titlePaint() = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
        textAlign = Paint.Align.CENTER
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun footerPaint() = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
        textAlign = Paint.Align.CENTER
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawColumn(
        canvas: Canvas,
        centerX: Int,
        qr: Bitmap?,
        title: String,
        footer: String,
        titlePaint: Paint,
        footerPaint: Paint
    ) {
        if (qr == null) return
        canvas.drawText(title, centerX.toFloat(), TITLE_HEIGHT.toFloat(), titlePaint)

        val qrLeft = centerX - QR_PX / 2
        val qrTop = TITLE_HEIGHT + PADDING
        canvas.drawBitmap(qr, qrLeft.toFloat(), qrTop.toFloat(), null)

        if (footer.isNotBlank()) {
            val footerBaseline = qrTop + QR_PX + PADDING + FOOTER_HEIGHT - 8
            canvas.drawText(footer, centerX.toFloat(), footerBaseline.toFloat(), footerPaint)
        }
    }

    private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    internal fun truncateForWidth(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
}
