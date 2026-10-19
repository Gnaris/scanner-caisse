package com.belijolie.scanner.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.R
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import com.belijolie.scanner.util.CategoryTranslator
import com.belijolie.scanner.util.suggestNextSageCode

@Composable
fun CategoriesScreen(
    vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.Factory())
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<Category?>(null) }
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_add))
            }
        }
    ) { padding ->
        if (categories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    stringResource(R.string.category_empty),
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
                items(categories, key = { it.id }) { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            val meta = buildList {
                                c.nameEn?.takeIf { it.isNotBlank() }?.let { add("EN : $it") }
                                c.sageArticleCode?.takeIf { it.isNotBlank() }?.let { add("Sage : $it") }
                                c.barcodePrefix?.takeIf { it.isNotBlank() }?.let { add("Préfixe : $it") }
                            }.joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                        IconButton(onClick = { categoryToEdit = c }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
                        }
                        IconButton(onClick = { categoryToDelete = c }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        CategoryEditDialog(
            title = stringResource(R.string.category_add_title),
            confirmLabel = stringResource(R.string.common_add),
            initialName = "",
            initialNameEn = "",
            initialSage = suggestNextSageCode(categories),
            initialBarcodePrefix = "",
            onDismiss = { showAdd = false },
            onConfirm = { name, nameEn, sage, prefix ->
                vm.add(name, nameEn, sage, prefix)
                showAdd = false
            }
        )
    }

    categoryToEdit?.let { cat ->
        CategoryEditDialog(
            title = stringResource(R.string.category_edit_title),
            confirmLabel = stringResource(R.string.common_save),
            initialName = cat.name,
            initialNameEn = cat.nameEn.orEmpty(),
            initialSage = cat.sageArticleCode.orEmpty(),
            initialBarcodePrefix = cat.barcodePrefix.orEmpty(),
            onDismiss = { categoryToEdit = null },
            onConfirm = { name, nameEn, sage, prefix ->
                vm.update(cat, name, nameEn, sage, prefix)
                categoryToEdit = null
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

    categoryToDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text(stringResource(R.string.category_delete_title)) },
            text = { Text(stringResource(R.string.category_delete_body, cat.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(cat)
                    categoryToDelete = null
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = Color(0xFFB91C1C),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun CategoryEditDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialNameEn: String,
    initialSage: String,
    initialBarcodePrefix: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, nameEn: String, sage: String, barcodePrefix: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var nameEn by remember { mutableStateOf(initialNameEn) }
    // Mémorise la dernière suggestion acceptée pour ne pas écraser une saisie
    // manuelle de l'utilisateur. On ne remplit nameEn automatiquement que tant
    // que son contenu correspond encore à la précédente suggestion.
    var lastSuggestion by remember { mutableStateOf(initialNameEn) }
    var sage by remember { mutableStateOf(initialSage) }
    var barcodePrefix by remember { mutableStateOf(initialBarcodePrefix) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { newName ->
                        name = newName
                        val suggestion = CategoryTranslator.suggestEnglishName(newName).orEmpty()
                        if (nameEn.isBlank() || nameEn == lastSuggestion) {
                            nameEn = suggestion
                            lastSuggestion = suggestion
                        }
                    },
                    label = stringResource(R.string.category_name_fr),
                    mode = KeyboardMode.TEXT
                )
                AppTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = stringResource(R.string.category_name_en),
                    mode = KeyboardMode.TEXT
                )
                AppTextField(
                    value = sage,
                    onValueChange = { sage = it },
                    label = stringResource(R.string.category_sage_code),
                    mode = KeyboardMode.TEXT
                )
                Text(
                    stringResource(R.string.category_sage_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                AppTextField(
                    value = barcodePrefix,
                    onValueChange = { barcodePrefix = it },
                    label = stringResource(R.string.category_barcode_prefix),
                    mode = KeyboardMode.NUMBER
                )
                Text(
                    stringResource(R.string.category_barcode_prefix_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, nameEn, sage, barcodePrefix) }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
