package com.belijolie.scanner.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.cart.CartLine
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.printer.TicketImage
import com.belijolie.scanner.ui.checkout.sortLines
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aperçu "fac-similé" du ticket avec les paramètres actuels + un petit panier
 * fictif. Objectif : vérifier avant d'imprimer que l'adresse, les QR codes et
 * la mise en page sont corrects — pas gâcher de papier. Les colonnes utilisent
 * exactement les mêmes largeurs que [com.belijolie.scanner.printer.EscPosPrinter]
 * pour que l'alignement reflète fidèlement le rendu papier.
 *
 * Attention : les constantes de mise en page doivent rester synchronisées avec
 * `EscPosPrinter`. Si tu changes CHARS_PER_LINE ou les largeurs de colonnes
 * là-bas, mets à jour les constantes ci-dessous.
 */
@Composable
fun TicketPreviewSheet(
    settings: AppSettings,
    onDismiss: () -> Unit
) {
    val sampleLines = listOf(
        CartLine(productId = 1L, barcode = "ex-1", categoryName = "Robe", unitPriceCents = 2917, quantity = 1),
        CartLine(productId = 2L, barcode = "ex-2", categoryName = "Pantalon", unitPriceCents = 2083, quantity = 2),
        CartLine(productId = 3L, barcode = "ex-3", categoryName = "Foulard", unitPriceCents = 1250, quantity = 1),
        CartLine(productId = 4L, barcode = "ex-4", categoryName = "Accessoires", unitPriceCents = 500, quantity = 3)
    )
    val sortedLines = remember { sortLines(sampleLines) }
    val totalHt = sortedLines.sumOf { it.unitPriceCents * it.quantity }
    val totalTva = Math.round(totalHt * 0.20).toInt()
    val totalTtc = totalHt + totalTva
    val totalQty = sortedLines.sumOf { it.quantity }

    val qrBitmap: Bitmap? = remember(settings.microstoreQr, settings.microstoreCode, settings.websiteUrl) {
        if (settings.microstoreQr.isNotBlank() || settings.websiteUrl.isNotBlank()) {
            TicketImage.composeDoubleQrSection(settings.microstoreQr, settings.microstoreCode, settings.websiteUrl)
        } else null
    }
    val reviewBitmap: Bitmap? = remember(settings.googleReviewUrl) {
        if (settings.googleReviewUrl.isNotBlank()) {
            TicketImage.composeReviewQrSection(settings.googleReviewUrl)
        } else null
    }
    val now = remember { SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.FRANCE).format(Date()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFE5E7EB))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Aperçu du ticket",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
            Text(
                "Simulation avec des produits d'exemple. Léger écart possible avec le papier.",
                fontSize = 11.sp,
                color = Color(0xFF4B5563)
            )
            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PaperText(settings.shopName.uppercase(), big = true, bold = true)
                    Spacer(Modifier.height(6.dp))
                    if (settings.address.isNotBlank()) PaperText(settings.address)
                    if (settings.addressComplement.isNotBlank()) PaperText(settings.addressComplement)
                    val cityLine = listOf(settings.postalCode, settings.city)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    if (cityLine.isNotBlank()) PaperText(cityLine)
                    if (settings.country.isNotBlank()) PaperText(settings.country)

                    PaperDivider()
                    MonoLine(now)
                    PaperDivider()

                    MonoLine(tableRow("Catégorie", "Qt", "P.U.", "HT"))
                    PaperDivider()

                    var currentCategory: String? = null
                    var categoryIndex = -1
                    sortedLines.forEach { line ->
                        if (line.categoryName != currentCategory) {
                            currentCategory = line.categoryName
                            categoryIndex++
                        }
                        val bold = categoryIndex % 2 == 1
                        val lineHt = line.unitPriceCents * line.quantity

                        val catChunks = line.categoryName.chunked(COL_CAT).ifEmpty { listOf("") }
                        catChunks.forEachIndexed { idx, chunk ->
                            val row = if (idx == 0) {
                                tableRow(
                                    chunk,
                                    "x" + line.quantity,
                                    formatPrice(line.unitPriceCents) + "€",
                                    formatPrice(lineHt) + "€"
                                )
                            } else tableRow(chunk, "", "", "")
                            MonoLine(row, bold = bold)
                        }
                    }

                    PaperDivider()

                    TotalLine("Quantité totale", "x$totalQty")
                    TotalLine("Total HT", "${formatPrice(totalHt)} €")
                    TotalLine("TVA totale (20%)", "${formatPrice(totalTva)} €")
                    TotalLine("Total TTC", "${formatPrice(totalTtc)} €", bold = true)

                    if (qrBitmap != null) {
                        PaperDivider()
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (reviewBitmap != null) {
                        PaperDivider()
                        Image(
                            bitmap = reviewBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// --- Mise en page synchronisée avec EscPosPrinter (voir docstring) ---

private const val COL_CAT = 18
private const val COL_QT = 4
private const val COL_PU = 8
private const val COL_HT = 9

private fun tableRow(cat: String, qt: String, pu: String, ht: String): String {
    val catCol = cat.take(COL_CAT).padEnd(COL_CAT)
    val qtCol = qt.padStart(COL_QT)
    val puCol = pu.padStart(COL_PU)
    val htCol = ht.padStart(COL_HT)
    return "$catCol $qtCol $puCol $htCol"
}

private fun formatPrice(cents: Int): String {
    val euros = cents / 100
    val remainder = cents % 100
    return "%d.%02d".format(euros, remainder)
}

@Composable
private fun PaperText(text: String, big: Boolean = false, bold: Boolean = false) {
    Text(
        text,
        fontSize = if (big) 18.sp else 13.sp,
        fontWeight = if (bold || big) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        color = Color(0xFF111111),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MonoLine(text: String, bold: Boolean = false) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = Color(0xFF111111),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PaperDivider() {
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF9CA3AF))
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TotalLine(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = Color(0xFF111111)
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = Color(0xFF111111)
        )
    }
}
