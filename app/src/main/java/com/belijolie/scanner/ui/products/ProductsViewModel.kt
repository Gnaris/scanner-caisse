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
            val cleaned = barcode.trim()
            if (cleaned.isEmpty()) return@launch
            val existing = db.productDao().findByBarcode(cleaned)
            if (existing != null) {
                _error.value = "Un produit avec le code-barres « $cleaned » existe déjà (${existing.category.name})."
                return@launch
            }
            runCatching {
                db.productDao().insert(
                    Product(barcode = cleaned, categoryId = categoryId, priceCents = priceCents)
                )
            }.onFailure {
                _error.value = "Impossible d'ajouter : code-barres déjà utilisé."
            }
        }
    }

    fun delete(product: Product) {
        viewModelScope.launch { db.productDao().deleteById(product.id) }
    }

    /**
     * Mise à jour d'un produit existant : barcode, catégorie, prix. Si le
     * nouveau barcode est déjà pris par un autre produit (unicité DB), on
     * remonte une erreur et on ne modifie rien.
     */
    fun update(
        product: Product,
        newBarcode: String,
        newCategoryId: Long,
        newPriceCents: Int,
    ) {
        viewModelScope.launch {
            val cleaned = newBarcode.trim()
            if (cleaned.isEmpty() || newCategoryId == 0L) return@launch
            if (!cleaned.equals(product.barcode, ignoreCase = true)) {
                val existing = db.productDao().findByBarcode(cleaned)
                if (existing != null && existing.product.id != product.id) {
                    _error.value = "Code-barres « $cleaned » déjà utilisé par ${existing.category.name}."
                    return@launch
                }
            }
            runCatching {
                db.productDao().update(
                    product.copy(
                        barcode = cleaned,
                        categoryId = newCategoryId,
                        priceCents = newPriceCents,
                    )
                )
            }.onFailure { _error.value = "Impossible de mettre à jour le produit." }
        }
    }

    fun dismissError() { _error.value = null }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProductsViewModel(ScannerApp.instance.db) as T
        }
    }
}
