package com.belijolie.scanner.ui.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.R
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import com.belijolie.scanner.util.CategoryTranslator
import kotlin.math.roundToInt

/**
 * Modal pour ajouter manuellement une ligne au canal actif.
 *
 * Un SEUL champ pour la catégorie + chips de catégories existantes au-dessus.
 * Champ nom anglais optionnel pour l'impression ticket EN (auto-rempli depuis
 * le dictionnaire interne tant que l'utilisateur n'a pas écrit sa propre valeur).
 * Taper une catégorie absente la crée à la volée côté caller.
 */
@Composable
fun AddManualProductSheet(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (categoryName: String, priceCents: Int, quantity: Int, nameEn: String?) -> Unit
) {
    var categoryText by remember { mutableStateOf("") }
    var nameEn by remember { mutableStateOf("") }
    var lastSuggestion by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("1") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF4F5F7))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.manual_sheet_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (categories.isNotEmpty()) {
                    Text(
                        stringResource(R.string.manual_existing_categories),
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    CategoryChipsRow(
                        categories = categories,
                        selected = categoryText,
                        onPick = { c ->
                            categoryText = c.name
                            // Si la catégorie choisie a déjà une traduction, on la propose
                            // (toujours modifiable à la main).
                            val suggestion = c.nameEn?.takeIf { it.isNotBlank() }
                                ?: CategoryTranslator.suggestEnglishName(c.name).orEmpty()
                            if (nameEn.isBlank() || nameEn == lastSuggestion) {
                                nameEn = suggestion
                                lastSuggestion = suggestion
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                AppTextField(
                    value = categoryText,
                    onValueChange = { newVal ->
                        categoryText = newVal
                        val suggestion = CategoryTranslator.suggestEnglishName(newVal).orEmpty()
                        if (nameEn.isBlank() || nameEn == lastSuggestion) {
                            nameEn = suggestion
                            lastSuggestion = suggestion
                        }
                    },
                    label = stringResource(R.string.manual_category_label),
                    mode = KeyboardMode.TEXT
                )
                Spacer(Modifier.height(12.dp))

                AppTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = stringResource(R.string.manual_name_en_label),
                    mode = KeyboardMode.TEXT
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        AppTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = stringResource(R.string.manual_price_label),
                            mode = KeyboardMode.DECIMAL
                        )
                    }
                    Box(modifier = Modifier.weight(0.6f)) {
                        AppTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it.filter { c -> c.isDigit() } },
                            label = stringResource(R.string.checkout_quantity),
                            mode = KeyboardMode.NUMBER
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        val cents = parsePriceCentsManual(priceText) ?: return@Button
                        val qty = qtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        onConfirm(
                            categoryText.trim(),
                            cents,
                            qty,
                            nameEn.trim().takeIf { it.isNotEmpty() }
                        )
                    },
                    enabled = categoryText.isNotBlank() && parsePriceCentsManual(priceText) != null && (qtyText.toIntOrNull()?.let { it >= 1 } == true),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        stringResource(R.string.manual_add_to_cart),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CategoryChipsRow(
    categories: List<Category>,
    selected: String,
    onPick: (Category) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.forEach { c ->
            val isSelected = c.name.equals(selected.trim(), ignoreCase = true)
            CategoryChip(
                label = c.name,
                selected = isSelected,
                onClick = { onPick(c) }
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) Color(0xFF4F46E5) else Color.White
    val fg = if (selected) Color.White else Color(0xFF111111)
    val border = if (selected) Color(0xFF4F46E5) else Color(0xFFD1D5DB)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

private fun parsePriceCentsManual(text: String): Int? {
    if (text.isBlank()) return null
    val normalized = text.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 100).roundToInt()
}
