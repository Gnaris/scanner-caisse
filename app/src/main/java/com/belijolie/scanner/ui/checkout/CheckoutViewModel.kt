package com.belijolie.scanner.ui.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.audio.ScanSounds
import com.belijolie.scanner.cart.ChannelsRepository
import com.belijolie.scanner.cart.ChannelsState
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.data.SettingsStore
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.printer.EscPosPrinter
import com.belijolie.scanner.scanner.ScanEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrintResult(val success: Boolean, val message: String)

class CheckoutViewModel(
    private val channelsRepo: ChannelsRepository,
    private val settingsStore: SettingsStore,
    private val db: AppDatabase
) : ViewModel() {

    val state: StateFlow<ChannelsState> = channelsRepo.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelsState())

    private val _unknownBarcode = MutableStateFlow<String?>(null)
    val unknownBarcode: StateFlow<String?> = _unknownBarcode.asStateFlow()

    val categories: StateFlow<List<Category>> = db.categoryDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val existingProducts: StateFlow<List<ProductWithCategory>> =
        db.productDao().observeAllWithCategory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _printResult = MutableStateFlow<PrintResult?>(null)
    val printResult: StateFlow<PrintResult?> = _printResult.asStateFlow()

    private val _isPrinting = MutableStateFlow(false)
    val isPrinting: StateFlow<Boolean> = _isPrinting.asStateFlow()

    init {
        // Collect barcodes scanned anywhere in the app while this VM is alive.
        viewModelScope.launch {
            ScanEventBus.events.collect { barcode -> handleScan(barcode) }
        }
    }

    private suspend fun handleScan(barcode: String) {
        // Ignore duplicate scans while the "unknown product" sheet is open.
        if (_unknownBarcode.value != null) return

        val st = channelsRepo.current()
        if (st.activeChannelId == null) {
            // Auto-create a first channel so a scan is never lost.
            channelsRepo.createChannel("Client 1")
        }
        val product = db.productDao().findByBarcode(barcode)
        if (product == null) {
            ScanSounds.playNotFound()
            _unknownBarcode.value = barcode
            return
        }
        ScanSounds.playFound()
        channelsRepo.addProductToActive(
            productId = product.product.id,
            barcode = product.product.barcode,
            categoryName = product.category.name,
            priceCents = product.product.priceCents
        )
    }

    /**
     * Create a new category on the fly from the "unknown product" sheet.
     * Returns the new category id so the caller can select it immediately.
     */
    fun createCategory(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { db.categoryDao().insert(Category(name = name.trim())) }
                .getOrNull() ?: return@launch
            onCreated(id)
        }
    }

    fun dismissUnknown() { _unknownBarcode.value = null }
    fun clearPrintResult() { _printResult.value = null }

    /**
     * Create a brand new product with the given barcode / category / price,
     * then add it to the active channel. Called from the "unknown product"
     * sheet when the user picks "Create".
     */
    fun createAndAddToCart(barcode: String, categoryId: Long, priceCents: Int) {
        viewModelScope.launch {
            val id = runCatching {
                db.productDao().insert(
                    Product(barcode = barcode.trim(), categoryId = categoryId, priceCents = priceCents)
                )
            }.getOrElse { return@launch }
            val categoryName = db.categoryDao().getById(categoryId)?.name ?: return@launch
            channelsRepo.addProductToActive(id, barcode.trim(), categoryName, priceCents)
            _unknownBarcode.value = null
        }
    }

    /**
     * Add an already-existing product to the active channel (does NOT touch
     * the database — the scanned barcode stays unassociated). Called from
     * the "unknown product" sheet when the user picks "Choose from list".
     */
    fun addExistingToCart(productId: Long) {
        viewModelScope.launch {
            val product = existingProducts.value.firstOrNull { it.product.id == productId } ?: return@launch
            channelsRepo.addProductToActive(
                productId = product.product.id,
                barcode = product.product.barcode,
                categoryName = product.category.name,
                priceCents = product.product.priceCents
            )
            _unknownBarcode.value = null
        }
    }

    fun newChannel() {
        viewModelScope.launch {
            val index = channelsRepo.current().channels.size + 1
            channelsRepo.createChannel("Client $index")
        }
    }

    fun selectChannel(id: String) {
        viewModelScope.launch { channelsRepo.setActive(id) }
    }

    fun renameChannel(id: String, newName: String) {
        viewModelScope.launch { channelsRepo.rename(id, newName) }
    }

    fun closeChannel(id: String) {
        viewModelScope.launch { channelsRepo.closeChannel(id) }
    }

    fun removeLine(productId: Long) {
        viewModelScope.launch { channelsRepo.removeLineFromActive(productId) }
    }

    /**
     * Manually add a line to the active channel (no DB product, no barcode).
     * Merges with an existing line if (category, price) matches.
     */
    fun addManualToCart(categoryName: String, priceCents: Int) {
        viewModelScope.launch {
            val st = channelsRepo.current()
            if (st.activeChannelId == null) {
                channelsRepo.createChannel("Client 1")
            }
            channelsRepo.addProductToActive(
                productId = -System.currentTimeMillis(),
                barcode = "",
                categoryName = categoryName.trim(),
                priceCents = priceCents
            )
        }
    }

    fun printActive() {
        viewModelScope.launch {
            val st = channelsRepo.current()
            val active = st.channels.firstOrNull { it.id == st.activeChannelId } ?: return@launch
            if (active.lines.isEmpty()) {
                _printResult.value = PrintResult(false, "Panier vide")
                return@launch
            }
            val settings: AppSettings = settingsStore.settings.first()
            _isPrinting.value = true
            val result = runCatching {
                EscPosPrinter.printReceipt(
                    settings = settings,
                    lines = sortLines(active.lines)
                )
            }
            _isPrinting.value = false
            if (result.isSuccess) {
                channelsRepo.bumpTicketNumber()
                _printResult.value = PrintResult(true, "Ticket imprimé")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Erreur inconnue"
                _printResult.value = PrintResult(false, "Impression KO : $msg")
            }
        }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = ScannerApp.instance
            @Suppress("UNCHECKED_CAST")
            return CheckoutViewModel(app.channelsRepo, app.settingsStore, app.db) as T
        }
    }
}

/**
 * Group identical categories together and sort them by their lightest
 * unit price. Inside each group, lines are sorted by unit price ascending.
 */
fun sortLines(lines: List<com.belijolie.scanner.cart.CartLine>): List<com.belijolie.scanner.cart.CartLine> {
    if (lines.isEmpty()) return lines
    val byCategory = lines.groupBy { it.categoryName }
    val sortedCategoryNames = byCategory.entries
        .sortedBy { it.value.minOf { l -> l.unitPriceCents } }
        .map { it.key }
    return sortedCategoryNames.flatMap { catName ->
        byCategory.getValue(catName).sortedBy { it.unitPriceCents }
    }
}
