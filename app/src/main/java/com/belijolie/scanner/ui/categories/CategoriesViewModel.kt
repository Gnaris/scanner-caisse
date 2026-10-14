package com.belijolie.scanner.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.entity.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(private val db: AppDatabase) : ViewModel() {

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun add(name: String, nameEn: String?, sageArticleCode: String?, barcodePrefix: String?) {
        viewModelScope.launch {
            val cleaned = name.trim()
            if (cleaned.isEmpty()) return@launch
            val existing = db.categoryDao().findByName(cleaned)
            if (existing != null) {
                _error.value = "La catégorie « ${existing.name} » existe déjà."
                return@launch
            }
            runCatching {
                db.categoryDao().insert(
                    Category(
                        name = cleaned,
                        nameEn = nameEn?.trim()?.takeIf { it.isNotEmpty() },
                        sageArticleCode = sageArticleCode?.trim()?.takeIf { it.isNotEmpty() },
                        barcodePrefix = barcodePrefix?.trim()?.takeIf { it.isNotEmpty() }
                    )
                )
            }.onFailure { _error.value = "Impossible d'ajouter cette catégorie." }
        }
    }

    fun delete(category: Category) {
        viewModelScope.launch {
            runCatching { db.categoryDao().deleteById(category.id) }
                .onFailure { _error.value = "Impossible de supprimer : des produits l'utilisent." }
        }
    }

    fun update(category: Category, newName: String, nameEn: String?, sageArticleCode: String?, barcodePrefix: String?) {
        viewModelScope.launch {
            val cleanedName = newName.trim()
            if (cleanedName.isEmpty()) return@launch
            // Vérifier conflit de nom si on renomme vers un autre nom
            if (!cleanedName.equals(category.name, ignoreCase = true)) {
                val existing = db.categoryDao().findByName(cleanedName)
                if (existing != null && existing.id != category.id) {
                    _error.value = "Une catégorie « ${existing.name} » existe déjà."
                    return@launch
                }
            }
            runCatching {
                db.categoryDao().update(
                    category.copy(
                        name = cleanedName,
                        nameEn = nameEn?.trim()?.takeIf { it.isNotEmpty() },
                        sageArticleCode = sageArticleCode?.trim()?.takeIf { it.isNotEmpty() },
                        barcodePrefix = barcodePrefix?.trim()?.takeIf { it.isNotEmpty() }
                    )
                )
            }.onFailure {
                _error.value = "Impossible de mettre à jour la catégorie."
            }
        }
    }

    fun dismissError() { _error.value = null }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return CategoriesViewModel(ScannerApp.instance.db) as T
        }
    }
}
