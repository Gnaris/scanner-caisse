package com.belijolie.scanner.ui.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * Modal to manually add a product line to the active channel.
 *
 * Category source is user's choice:
 *   - pick an existing one via the dropdown
 *   - or type a free-text category (useful for one-offs that don't warrant a DB entry)
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

                CategoryPicker(
                    categories = categories,
                    value = categoryText,
                    onValueChange = { categoryText = it }
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
private fun CategoryPicker(
    categories: List<Category>,
    value: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            "Catégorie (choisir ou taper)",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Text(
                    value.ifEmpty { if (categories.isEmpty()) "— Taper le nom —" else "— Choisir ou taper —" },
                    fontSize = 16.sp,
                    color = if (value.isEmpty()) Color.Gray else Color.Black
                )
            }
            Spacer(Modifier.padding(start = 6.dp))
            // Free-text alternative: opens the AZERTY keyboard directly.
            AppTextField(
                value = value,
                onValueChange = onValueChange,
                label = "Saisir",
                mode = KeyboardMode.TEXT,
                modifier = Modifier.weight(1f)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (categories.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Aucune catégorie — tape le nom à droite", color = Color.Gray) },
                    onClick = { expanded = false }
                )
            } else {
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        onClick = {
                            onValueChange(c.name)
                            expanded = false
                        }
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Effacer", color = Color(0xFFE11D48)) },
                onClick = {
                    onValueChange("")
                    expanded = false
                }
            )
        }
    }
}

private fun parsePriceCentsManual(text: String): Int? {
    if (text.isBlank()) return null
    val normalized = text.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 100).toInt()
}
