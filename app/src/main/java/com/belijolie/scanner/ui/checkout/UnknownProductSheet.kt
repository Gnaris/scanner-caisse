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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.R
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import com.belijolie.scanner.ui.products.decodePriceFromBarcode
import com.belijolie.scanner.util.CategoryTranslator
import com.belijolie.scanner.util.suggestNextSageCode

private enum class View { CREATE, PICK }

@Composable
fun UnknownProductSheet(
    barcode: String,
    categories: List<Category>,
    existingProducts: List<ProductWithCategory>,
    onDismiss: () -> Unit,
    onCreate: (barcode: String, categoryId: Long, priceCents: Int) -> Unit,
    onPick: (productId: Long) -> Unit,
    onCreateCategory: (name: String, nameEn: String?, sage: String?, onCreated: (Long) -> Unit) -> Unit
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
                            if (view == View.CREATE) stringResource(R.string.unknown_create_title)
                            else stringResource(R.string.unknown_pick_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (view == View.CREATE) {
                            Spacer(Modifier.height(4.dp))
                            BarcodeBadge(barcode)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (view == View.PICK) {
                    TextButton(onClick = { view = View.CREATE }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(Modifier.padding(start = 4.dp))
                        Text(stringResource(R.string.unknown_back_to_create), fontSize = 14.sp)
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
                                    stringResource(R.string.unknown_or_pick_from_list),
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
    onCreateCategory: (name: String, nameEn: String?, sage: String?, onCreated: (Long) -> Unit) -> Unit
) {
    // Pré-sélection par préfixe : si une catégorie a un `barcodePrefix` qui
    // matche le début du code-barres scanné, on la choisit par défaut. Sinon
    // on retombe sur la dernière catégorie créée.
    var categoryId by remember(categories.size, barcode) {
        val byPrefix = categories.firstOrNull { c ->
            val p = c.barcodePrefix?.trim().orEmpty()
            p.isNotEmpty() && barcode.startsWith(p)
        }
        mutableLongStateOf(byPrefix?.id ?: categories.lastOrNull()?.id ?: 0L)
    }
    val autoCents = remember(barcode) { decodePriceFromBarcode(barcode) }
    var priceText by remember(barcode) {
        mutableStateOf(autoCents?.let { "%d.%02d".format(it / 100, it % 100) } ?: "")
    }
    var creatingCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryNameEn by remember { mutableStateOf("") }
    // Mémorise la dernière suggestion EN pour ne pas écraser une saisie manuelle
    // (même pattern que CategoryEditDialog dans l'écran Catégories).
    var lastEnSuggestion by remember { mutableStateOf("") }
    // Suggestion Sage recalculée à chaque ouverture de la mini-form : prend le
    // snapshot `categories` courant et retourne le prochain ART000X libre.
    var newCategorySage by remember(creatingCategory, categories.size) {
        mutableStateOf(if (creatingCategory) suggestNextSageCode(categories) else "")
    }

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
                        stringResource(R.string.unknown_new_category),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E3A8A)
                    )
                    Spacer(Modifier.height(6.dp))
                    AppTextField(
                        value = newCategoryName,
                        onValueChange = { v ->
                            newCategoryName = v
                            // Suggère une traduction EN tant que l'utilisateur
                            // n'a pas tapé son propre nameEn (ou qu'il contient
                            // encore notre précédente suggestion).
                            val suggestion = CategoryTranslator.suggestEnglishName(v).orEmpty()
                            if (newCategoryNameEn.isBlank() || newCategoryNameEn == lastEnSuggestion) {
                                newCategoryNameEn = suggestion
                                lastEnSuggestion = suggestion
                            }
                        },
                        label = stringResource(R.string.unknown_category_name_label),
                        mode = KeyboardMode.TEXT
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = newCategoryNameEn,
                        onValueChange = { newCategoryNameEn = it },
                        label = stringResource(R.string.category_name_en),
                        mode = KeyboardMode.TEXT
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = newCategorySage,
                        onValueChange = { newCategorySage = it },
                        label = stringResource(R.string.category_sage_code),
                        mode = KeyboardMode.TEXT
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                creatingCategory = false
                                newCategoryName = ""
                                newCategoryNameEn = ""
                                newCategorySage = ""
                                lastEnSuggestion = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.common_cancel)) }
                        Button(
                            onClick = {
                                val name = newCategoryName.trim()
                                if (name.isNotEmpty()) {
                                    onCreateCategory(
                                        name,
                                        newCategoryNameEn.trim().takeIf { it.isNotEmpty() },
                                        newCategorySage.trim().takeIf { it.isNotEmpty() }
                                    ) { newId ->
                                        categoryId = newId
                                    }
                                    creatingCategory = false
                                    newCategoryName = ""
                                    newCategoryNameEn = ""
                                    newCategorySage = ""
                                    lastEnSuggestion = ""
                                }
                            },
                            enabled = newCategoryName.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.unknown_create_short), color = Color.White) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AppTextField(
            value = priceText,
            onValueChange = { priceText = it },
            label = if (autoCents != null) stringResource(R.string.unknown_price_auto)
            else stringResource(R.string.product_price),
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
            Text(stringResource(R.string.unknown_create_cta), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
    val fallbackLabel = if (categories.isEmpty())
        stringResource(R.string.unknown_category_dropdown_empty)
    else stringResource(R.string.unknown_category_dropdown_pick)
    val selected = categories.firstOrNull { it.id == selectedId }?.name ?: fallbackLabel

    Column {
        Text(stringResource(R.string.checkout_header_category), fontSize = 12.sp, color = Color.Gray)
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
                            stringResource(R.string.unknown_new_category),
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
        Text(stringResource(R.string.unknown_no_products), color = Color.Gray)
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
