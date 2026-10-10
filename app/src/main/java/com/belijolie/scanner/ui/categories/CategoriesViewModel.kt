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

    fun add(name: String) {
        viewModelScope.launch {
            runCatching { db.categoryDao().insert(Category(name = name.trim())) }
                .onFailure { _error.value = "Impossible d'ajouter cette catégorie." }
        }
    }

    fun delete(category: Category) {
        viewModelScope.launch {
            runCatching { db.categoryDao().deleteById(category.id) }
                .onFailure { _error.value = "Impossible de supprimer : des produits l'utilisent." }
        }
    }

    fun rename(category: Category, newName: String) {
        viewModelScope.launch {
            runCatching {
                db.categoryDao().update(category.copy(name = newName.trim()))
            }.onFailure {
                _error.value = "Impossible de renommer : nom déjà utilisé ?"
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
