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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.R
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.scanner.ScanEventBus
import com.belijolie.scanner.ui.checkout.formatEuro
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    vm: ProductsViewModel = viewModel(factory = ProductsViewModel.Factory())
) {
    val products by vm.products.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var productToEdit by remember { mutableStateOf<ProductWithCategory?>(null) }
    var productToDelete by remember { mutableStateOf<ProductWithCategory?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_add))
            }
        }
    ) { padding ->
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    stringResource(R.string.product_empty),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(products, key = { it.product.id }) { p ->
                    ProductRow(
                        p,
                        onEdit = { productToEdit = p },
                        onDelete = { productToDelete = p },
                    )
                }
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

    productToEdit?.let { pwc ->
        EditProductDialog(
            product = pwc,
            categories = categories,
            onDismiss = { productToEdit = null },
            onConfirm = { barcode, categoryId, cents ->
                vm.update(pwc.product, barcode, categoryId, cents)
                productToEdit = null
            }
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.common_ok)) } }
        )
    }

    productToDelete?.let { pwc ->
        AlertDialog(
            onDismissRequest = { productToDelete = null },
            title = { Text(stringResource(R.string.product_delete_title)) },
            text = {
                Text(stringResource(
                    R.string.product_delete_body,
                    pwc.category.name,
                    pwc.product.barcode,
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(pwc.product)
                    productToDelete = null
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = Color(0xFFB91C1C),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { productToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProductRow(p: ProductWithCategory, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(p.category.name, fontWeight = FontWeight.SemiBold)
            Text(p.product.barcode, color = Color.Gray)
        }
        Text(formatEuro(p.product.priceCents), fontWeight = FontWeight.Bold)
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
        }
    }
    HorizontalDivider()
}

/**
 * Dialog d'édition d'un produit existant. Mêmes champs que l'ajout (barcode,
 * catégorie, prix) préremplis depuis le produit cliqué.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProductDialog(
    product: ProductWithCategory,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (barcode: String, categoryId: Long, priceCents: Int) -> Unit
) {
    var barcode by remember { mutableStateOf(product.product.barcode) }
    var priceText by remember {
        mutableStateOf("%d.%02d".format(product.product.priceCents / 100, product.product.priceCents % 100))
    }
    var categoryId by remember { mutableLongStateOf(product.category.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.product_edit_title)) },
        text = {
            Column {
                AppTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = stringResource(R.string.product_barcode),
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
                    label = stringResource(R.string.product_price),
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
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
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
        title = { Text(stringResource(R.string.manual_sheet_title)) },
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
                    label = stringResource(R.string.product_barcode),
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
                    label = stringResource(R.string.product_price),
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
            ) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
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
            stringResource(R.string.product_no_category_hint),
            color = Color.Red
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }?.name ?: "—"

    Column {
        Text(stringResource(R.string.checkout_header_category), fontSize = 12.sp, color = Color.Gray)
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
    return (value * 100).roundToInt()
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
