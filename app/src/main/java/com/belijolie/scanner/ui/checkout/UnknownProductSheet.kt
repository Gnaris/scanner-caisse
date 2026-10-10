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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import com.belijolie.scanner.ui.products.decodePriceFromBarcode

private enum class View { CREATE, PICK }

@Composable
fun UnknownProductSheet(
    barcode: String,
    categories: List<Category>,
    existingProducts: List<ProductWithCategory>,
    onDismiss: () -> Unit,
    onCreate: (barcode: String, categoryId: Long, priceCents: Int) -> Unit,
    onPick: (productId: Long) -> Unit,
    onCreateCategory: (name: String, onCreated: (Long) -> Unit) -> Unit
) {
    var view by remember { mutableStateOf(View.CREATE) }

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
                // Header with close button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (view == View.CREATE) "Produit inconnu — à créer" else "Choisir un produit existant",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (view == View.CREATE) {
                            Spacer(Modifier.height(4.dp))
                            BarcodeBadge(barcode)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (view == View.PICK) {
                    TextButton(onClick = { view = View.CREATE }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(Modifier.padding(start = 4.dp))
                        Text("Retour à la création", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    PickList(existingProducts, onPick)
                } else {
                    CreateForm(
                        barcode = barcode,
                        categories = categories,
                        onConfirm = { catId, cents -> onCreate(barcode, catId, cents) },
                        onCreateCategory = onCreateCategory
                    )
                    if (existingProducts.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(onClick = { view = View.PICK }) {
                                Text(
                                    "Ou choisir dans la liste →",
                                    textDecoration = TextDecoration.Underline,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BarcodeBadge(barcode: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF111827), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            barcode,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CreateForm(
    barcode: String,
    categories: List<Category>,
    onConfirm: (categoryId: Long, priceCents: Int) -> Unit,
    onCreateCategory: (name: String, onCreated: (Long) -> Unit) -> Unit
) {
    var categoryId by remember(categories.size) {
        mutableLongStateOf(categories.lastOrNull()?.id ?: 0L)
    }
    val autoCents = remember(barcode) { decodePriceFromBarcode(barcode) }
    var priceText by remember(barcode) {
        mutableStateOf(autoCents?.let { "%d.%02d".format(it / 100, it % 100) } ?: "")
    }
    var creatingCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }

    Column {
        CategoryDropdown(
            categories = categories,
            selectedId = categoryId,
            onSelected = { categoryId = it },
            onCreateCategoryClick = { creatingCategory = true }
        )

        if (creatingCategory) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE0E7FF), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "Nouvelle catégorie",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E3A8A)
                    )
                    Spacer(Modifier.height(6.dp))
                    AppTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = "Nom de la catégorie",
                        mode = KeyboardMode.TEXT
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { creatingCategory = false; newCategoryName = "" },
                            modifier = Modifier.weight(1f)
                        ) { Text("Annuler") }
                        Button(
                            onClick = {
                                val name = newCategoryName.trim()
                                if (name.isNotEmpty()) {
                                    onCreateCategory(name) { newId ->
                                        categoryId = newId
                                    }
                                    creatingCategory = false
                                    newCategoryName = ""
                                }
                            },
                            enabled = newCategoryName.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Créer", color = Color.White) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AppTextField(
            value = priceText,
            onValueChange = { priceText = it },
            label = if (autoCents != null) "Prix (€) — auto-décodé" else "Prix (€)",
            mode = KeyboardMode.DECIMAL
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val cents = parsePriceCents(priceText) ?: return@Button
                onConfirm(categoryId, cents)
            },
            enabled = categoryId != 0L && parsePriceCents(priceText) != null,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
            Spacer(Modifier.padding(start = 8.dp))
            Text("Créer et ajouter au panier", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryDropdown(
    categories: List<Category>,
    selectedId: Long,
    onSelected: (Long) -> Unit,
    onCreateCategoryClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }?.name
        ?: if (categories.isEmpty()) "— Aucune catégorie —" else "— Choisir —"

    Column {
        Text("Catégorie", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Text(selected, fontSize = 16.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    onClick = { onSelected(c.id); expanded = false }
                )
            }
            if (categories.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = Color(0xFF4F46E5)
                        )
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            "Nouvelle catégorie",
                            color = Color(0xFF4F46E5),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onCreateCategoryClick()
                }
            )
        }
    }
}

@Composable
private fun PickList(
    products: List<ProductWithCategory>,
    onPick: (Long) -> Unit
) {
    if (products.isEmpty()) {
        Text("Aucun produit dans la base pour l'instant.", color = Color.Gray)
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
    ) {
        items(products, key = { it.product.id }) { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(p.product.id) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.category.name, fontWeight = FontWeight.SemiBold)
                    Text(p.product.barcode, color = Color.Gray, fontSize = 12.sp)
                }
                Text(formatEuro(p.product.priceCents), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
        }
    }
}

private fun parsePriceCents(text: String): Int? {
    if (text.isBlank()) return null
    val normalized = text.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 100).toInt()
}
