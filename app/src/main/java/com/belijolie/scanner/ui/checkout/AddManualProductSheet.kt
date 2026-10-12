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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

/**
 * Modal pour ajouter manuellement une ligne au canal actif.
 *
 * Design simplifié : un SEUL champ pour la catégorie (saisie libre) + une
 * rangée de chips cliquables au-dessus pour sélectionner rapidement une
 * catégorie existante. Taper dedans crée une nouvelle catégorie à la volée
 * si elle n'existe pas (après validation côté caller).
 */
@Composable
fun AddManualProductSheet(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (categoryName: String, priceCents: Int) -> Unit
) {
    var categoryText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }

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
                        "Ajouter un produit",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (categories.isNotEmpty()) {
                    Text(
                        "Catégories existantes",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    CategoryChipsRow(
                        categories = categories,
                        selected = categoryText,
                        onPick = { categoryText = it.name }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                AppTextField(
                    value = categoryText,
                    onValueChange = { categoryText = it },
                    label = "Catégorie (choisir ou taper)",
                    mode = KeyboardMode.TEXT
                )
                Spacer(Modifier.height(12.dp))

                AppTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = "Prix (€)",
                    mode = KeyboardMode.DECIMAL
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        val cents = parsePriceCentsManual(priceText) ?: return@Button
                        onConfirm(categoryText.trim(), cents)
                    },
                    enabled = categoryText.isNotBlank() && parsePriceCentsManual(priceText) != null,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        "Ajouter au panier",
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
    return (value * 100).toInt()
}
