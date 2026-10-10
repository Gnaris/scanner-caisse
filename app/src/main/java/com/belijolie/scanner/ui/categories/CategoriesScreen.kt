package com.belijolie.scanner.ui.categories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

@Composable
fun CategoriesScreen(
    vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.Factory())
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var categoryToRename by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter")
            }
        }
    ) { padding ->
        if (categories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "Aucune catégorie. Appuie sur + pour en créer.",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Gray
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
                        Text(c.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { categoryToRename = c }) {
                            Icon(Icons.Default.Edit, contentDescription = "Renommer")
                        }
                        IconButton(onClick = { vm.delete(c) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Nouvelle catégorie") },
            text = {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Nom",
                    mode = KeyboardMode.TEXT
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.add(name)
                        showAdd = false
                    }
                ) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } }
        )
    }

    categoryToRename?.let { cat ->
        var newName by remember(cat.id) { mutableStateOf(cat.name) }
        AlertDialog(
            onDismissRequest = { categoryToRename = null },
            title = { Text("Renommer la catégorie") },
            text = {
                AppTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = "Nouveau nom",
                    mode = KeyboardMode.TEXT
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName != cat.name,
                    onClick = {
                        vm.rename(cat, newName)
                        categoryToRename = null
                    }
                ) { Text("Renommer") }
            },
            dismissButton = {
                TextButton(onClick = { categoryToRename = null }) { Text("Annuler") }
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
