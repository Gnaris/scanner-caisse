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
 * Builds the composite bitmap for the "QR codes side-by-side" section at the
 * bottom of the receipt: left = Microstore (title + QR + access code),
 * right = Boutique en ligne (title + QR + URL).
 *
 * The resulting bitmap is black & white, sized for the printer's dot width.
 */
object TicketImage {

    /**
     * Largeur du bitmap = largeur imprimable du MUNBYN ITPP047 : 72 mm × 8 dots/mm
     * = 576 dots. En conjonction avec `[C]<img>` dans EscPosPrinter, l'image est
     * centrée pile sur la bande imprimée. Chaque QR est placé à WIDTH_PX/4 et
     * WIDTH_PX*3/4 — centrés dans leur demi-bande, donc symétriques sur le papier.
     */
    private const val WIDTH_PX = 576

    private const val QR_PX = 180
    private const val PADDING = 8
    private const val TITLE_HEIGHT = 32
    private const val FOOTER_HEIGHT = 42

    /**
     * Section "Avis Google" imprimée en bas de ticket : titre d'invitation
     * centré + QR centré + sous-titre. L'URL (ex. g.page/r/XXX/review) ouvre
     * directement la page d'avis Google au scan.
     */
    fun composeReviewQrSection(url: String): Bitmap {
        val qrSize = 220
        val titleHeight = 44
        val subtitleHeight = 42
        val height = titleHeight + PADDING + qrSize + PADDING + subtitleHeight + PADDING

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
        canvas.drawText("LAISSEZ UN AVIS !", centerX.toFloat(), (titleHeight - 10).toFloat(), titlePaint)

        val qr = generateQrBitmap(url, qrSize)
        val qrLeft = centerX - qrSize / 2
        val qrTop = titleHeight + PADDING
        canvas.drawBitmap(qr, qrLeft.toFloat(), qrTop.toFloat(), null)

        canvas.drawText(
            "Scannez pour nous noter sur Google",
            centerX.toFloat(),
            (qrTop + qrSize + PADDING + subtitleHeight - 10).toFloat(),
            subtitlePaint
        )

        return bitmap
    }

    fun composeDoubleQrSection(
        microstoreQr: String,
        microstoreCode: String,
        websiteUrl: String
    ): Bitmap {
        val leftQr = if (microstoreQr.isNotBlank()) generateQrBitmap(microstoreQr, QR_PX) else null
        val rightQr = if (websiteUrl.isNotBlank()) generateQrBitmap(websiteUrl, QR_PX) else null

        val height = TITLE_HEIGHT + PADDING + QR_PX + PADDING + FOOTER_HEIGHT + PADDING

        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            textAlign = Paint.Align.CENTER
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val footerPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            textAlign = Paint.Align.CENTER
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val leftCenterX = WIDTH_PX / 4
        val rightCenterX = WIDTH_PX * 3 / 4

        drawColumn(
            canvas = canvas,
            centerX = leftCenterX,
            qr = leftQr,
            title = "MICROSTORE",
            footer = microstoreCode.ifBlank { "" },
            titlePaint = titlePaint,
            footerPaint = footerPaint
        )
        drawColumn(
            canvas = canvas,
            centerX = rightCenterX,
            qr = rightQr,
            title = "BOUTIQUE EN LIGNE",
            footer = truncateForWidth(websiteUrl, maxChars = 24),
            titlePaint = titlePaint,
            footerPaint = footerPaint
        )

        return bitmap
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

    private fun truncateForWidth(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
}
