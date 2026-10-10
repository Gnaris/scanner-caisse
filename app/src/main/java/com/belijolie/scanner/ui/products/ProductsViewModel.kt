package com.belijolie.scanner.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.ProductWithCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProductsViewModel(
    private val db: AppDatabase
) : ViewModel() {

    val products: StateFlow<List<ProductWithCategory>> =
        db.productDao().observeAllWithCategory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun add(barcode: String, categoryId: Long, priceCents: Int) {
        viewModelScope.launch {
            runCatching {
                db.productDao().insert(
                    Product(barcode = barcode.trim(), categoryId = categoryId, priceCents = priceCents)
                )
            }.onFailure {
                _error.value = "Impossible d'ajouter : code-barres déjà utilisé ?"
            }
        }
    }

    fun delete(product: Product) {
        viewModelScope.launch { db.productDao().deleteById(product.id) }
    }

    fun dismissError() { _error.value = null }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProductsViewModel(ScannerApp.instance.db) as T
        }
    }
}
