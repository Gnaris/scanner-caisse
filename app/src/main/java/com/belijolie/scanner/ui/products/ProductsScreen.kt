package com.belijolie.scanner.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.scanner.ScanEventBus
import com.belijolie.scanner.ui.checkout.formatEuro
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    vm: ProductsViewModel = viewModel(factory = ProductsViewModel.Factory())
) {
    val products by vm.products.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter")
            }
        }
    ) { padding ->
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "Aucun produit. Appuie sur + pour en créer.",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(products, key = { it.product.id }) { p -> ProductRow(p, onDelete = { vm.delete(p.product) }) }
            }
        }
    }

    if (showAdd) {
        AddProductDialog(
            categories = categories,
            onDismiss = { showAdd = false },
            onConfirm = { barcode, categoryId, cents ->
                vm.add(barcode, categoryId, cents)
                showAdd = false
            }
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("Erreur") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } }
        )
    }
}

@Composable
private fun ProductRow(p: ProductWithCategory, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(p.category.name, fontWeight = FontWeight.SemiBold)
            Text(p.product.barcode, color = Color.Gray)
        }
        Text(formatEuro(p.product.priceCents), fontWeight = FontWeight.Bold)
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProductDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (barcode: String, categoryId: Long, priceCents: Int) -> Unit
) {
    var barcode by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var categoryId by remember { mutableLongStateOf(categories.firstOrNull()?.id ?: 0L) }

    // When this dialog is open, listen for scans to auto-fill the barcode
    // AND auto-decode the price from the trailing digits (shop convention:
    // the first 5 chars are a prefix, the rest are the price in cents).
    LaunchedEffect(Unit) {
        ScanEventBus.events.collect { scanned ->
            barcode = scanned
            decodePriceFromBarcode(scanned)?.let { cents ->
                priceText = "%d.%02d".format(cents / 100, cents % 100)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau produit") },
        text = {
            Column {
                AppTextField(
                    value = barcode,
                    onValueChange = { newValue ->
                        barcode = newValue
                        decodePriceFromBarcode(newValue)?.let { cents ->
                            priceText = "%d.%02d".format(cents / 100, cents % 100)
                        }
                    },
                    label = "Code-barres (ou scan)",
                    mode = KeyboardMode.NUMBER
                )
                Spacer(Modifier.height(12.dp))

                CategorySelector(
                    categories = categories,
                    selectedId = categoryId,
                    onSelected = { categoryId = it }
                )
                Spacer(Modifier.height(12.dp))

                AppTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = "Prix (€)",
                    mode = KeyboardMode.DECIMAL
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = barcode.isNotBlank() && categoryId != 0L && parseCents(priceText) != null,
                onClick = {
                    val cents = parseCents(priceText) ?: return@TextButton
                    onConfirm(barcode.trim(), categoryId, cents)
                }
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun CategorySelector(
    categories: List<Category>,
    selectedId: Long,
    onSelected: (Long) -> Unit
) {
    if (categories.isEmpty()) {
        Text(
            "Crée d'abord une catégorie dans l'onglet « Catégories ».",
            color = Color.Red
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }?.name ?: "— Choisir —"

    Column {
        Text("Catégorie", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 14.dp)
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
        }
    }
}

private fun parseCents(text: String): Int? {
    if (text.isBlank()) return null
    val normalized = text.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 100).toInt()
}

/**
 * Price-embedded barcode convention used by the shop:
 *   - 5 leading characters = store-defined prefix (any digits)
 *   - everything after = price in cents
 *
 * Examples:
 *   "11111850"   -> prefix "11111", cents 850   -> 8.50 €
 *   "111111150"  -> prefix "11111", cents 1150  -> 11.50 €
 *   "23456750"   -> prefix "23456", cents 750   -> 7.50 €
 *
 * External barcodes (EAN-13 like "3017620422003") would decode to an
 * unreasonable amount — we cap at 999.99 € (99 999 cents) and return null
 * above that, so the price field stays empty for the user to fill in.
 */
internal fun decodePriceFromBarcode(barcode: String): Int? {
    if (barcode.length <= 5) return null
    val rest = barcode.substring(5)
    val cents = rest.toIntOrNull() ?: return null
    if (cents <= 0 || cents > 99_999) return null
    return cents
}
