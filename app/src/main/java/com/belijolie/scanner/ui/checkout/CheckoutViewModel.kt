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
import com.belijolie.scanner.data.DeviceSession
import com.belijolie.scanner.data.entity.Device
import com.belijolie.scanner.data.SettingsStore
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.R
import com.belijolie.scanner.export.ChannelExporter
import com.belijolie.scanner.export.ExportFormat
import com.belijolie.scanner.export.SageInvoiceRef
import com.belijolie.scanner.export.SageMode
import com.belijolie.scanner.printer.EscPosPrinter
import com.belijolie.scanner.printer.TicketLang
import com.belijolie.scanner.scanner.ScanEventBus
import com.belijolie.scanner.sync.SyncEngine
import com.belijolie.scanner.util.toHumanMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrintResult(val success: Boolean, val message: String)

/**
 * Snapshot du dernier produit ajouté au panier via scan (ou création via le
 * modal "produit inconnu"). Utilisé pour afficher le mini bloc "dernier scan"
 * au-dessus du panier et permettre une édition rapide (catégorie / quantité
 * / prix) — l'édition annule le scan précédent et re-rejoue avec les
 * nouvelles valeurs.
 *
 * Garde le [productId] et le [barcode] pour que l'ajout corrigé puisse
 * continuer à référencer le produit DB existant.
 */
data class LastScan(
    val productId: String?,
    val barcode: String,
    val categoryName: String,
    val priceCents: Int,
    val quantity: Int,
    val categoryNameEn: String? = null,
    val nameEn: String? = null,
    val sageArticleCode: String? = null,
)

class CheckoutViewModel(
    private val channelsRepo: ChannelsRepository,
    private val settingsStore: SettingsStore,
    private val db: AppDatabase,
    private val session: DeviceSession,
) : ViewModel() {

    /** Caisse ouverte (nom affiché en haut de la liste des canaux). */
    val currentDevice: StateFlow<Device?> = session.currentDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ferme la caisse → retour à l'écran de choix. */
    fun leaveDevice() {
        viewModelScope.launch { session.leave() }
    }

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

    private val _exportResult = MutableStateFlow<PrintResult?>(null)
    val exportResult: StateFlow<PrintResult?> = _exportResult.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    /**
     * Multiplicateur de quantité pour le prochain scan. Mis à 1 par défaut,
     * modifié via l'UI (petit carré à côté du bouton "Ajouter produit"), reset
     * à 1 automatiquement après chaque scan réussi pour éviter d'empiler
     * accidentellement de grosses quantités sur les scans suivants.
     */
    private val _scanMultiplier = MutableStateFlow(1)
    val scanMultiplier: StateFlow<Int> = _scanMultiplier.asStateFlow()

    fun setScanMultiplier(n: Int) {
        _scanMultiplier.value = n.coerceAtLeast(1)
    }

    /** Mode retrait : chaque scan enlève l'article du panier au lieu de l'ajouter. */
    private val _removeMode = MutableStateFlow(false)
    val removeMode: StateFlow<Boolean> = _removeMode.asStateFlow()

    fun toggleRemoveMode() {
        _removeMode.value = !_removeMode.value
    }

    /**
     * Retire [qty] exemplaires du produit scanné de la ligne correspondante
     * (même catégorie + même prix). Bip d'erreur + message si le produit est
     * inconnu ou absent du panier.
     */
    private suspend fun removeScanned(barcode: String) {
        val ctx = ScannerApp.instance.applicationContext
        val product = db.productDao().findByBarcode(barcode)
        val st = channelsRepo.current()
        val channel = st.channels.firstOrNull { it.id == st.activeChannelId }
        val key = product?.let { com.belijolie.scanner.cart.lineKey(it.category.name, it.product.priceCents) }
        val lines = channel?.lines.orEmpty()
        // Par produit, puis par code-barres, puis par (catégorie, prix) : la
        // catégorie a pu être renommée depuis l'ajout au panier.
        val line = lines.firstOrNull { product != null && it.productId == product.product.id }
            ?: lines.firstOrNull { it.barcode == barcode }
            ?: lines.firstOrNull { it.key == key }
        if (line == null) {
            ScanSounds.playNotFound()
            _printResult.value = PrintResult(false, ctx.getString(R.string.checkout_remove_not_in_cart))
            return
        }
        val qty = _scanMultiplier.value.coerceAtLeast(1)
        channelsRepo.setLineQuantityInActive(line.key, line.quantity - qty)
        ScanSounds.playFound()
        // Le bloc « dernier scan » ne concerne que les ajouts
        _lastScan.value = null
    }

    private val _lastScan = MutableStateFlow<LastScan?>(null)
    val lastScan: StateFlow<LastScan?> = _lastScan.asStateFlow()

    /**
     * Canal que CE VM précisément s'attend à voir actif. Nav Compose garde
     * les VMs de l'écran précédent (liste des canaux) vivants quand on
     * navigue vers le détail : chaque VM a son propre `collect` sur
     * [ScanEventBus] → sans filtre, 2 VMs traitent le même scan en parallèle
     * et le produit arrive 2x dans le panier. Ici chaque VM compare son
     * [expectedChannelId] local avec l'[activeChannelId] du repo (partagé)
     * — seul celui qui "possède" vraiment le détail courant process le scan.
     * Le VM de la liste a `expectedChannelId == null` → skip tout scan.
     */
    private var expectedChannelId: String? = null

    init {
        // Collect barcodes scanned anywhere in the app while this VM is alive.
        viewModelScope.launch {
            ScanEventBus.events.collect { barcode -> handleScan(barcode) }
        }
    }

    private suspend fun handleScan(barcode: String) {
        // Ignore duplicate scans while the "unknown product" sheet is open.
        if (_unknownBarcode.value != null) return
        // QR de configuration de la synchro : traité par ScannerApp, pas un produit.
        if (SyncEngine.isConfigScan(barcode)) return

        val st = channelsRepo.current()
        // N'intervient que si ce VM est bien celui du canal courant. Évite
        // qu'un VM en back-stack process aussi le même scan (double qty) et
        // garantit que `_lastScan` / `_unknownBarcode` / `_scanMultiplier`
        // utilisés sont ceux de l'écran que l'utilisateur voit vraiment.
        if (expectedChannelId == null || expectedChannelId != st.activeChannelId) return
        // Nouvelle UX : pas d'auto-création de canal. L'utilisateur passe
        // forcément par la liste → bouton « Créer un canal » → détail. Un
        // scan hors d'un canal ouvert est ignoré (décision produit).
        if (st.activeChannelId == null) return
        if (_removeMode.value) return removeScanned(barcode)
        val product = db.productDao().findByBarcode(barcode)
        if (product == null) {
            ScanSounds.playNotFound()
            _unknownBarcode.value = barcode
            return
        }
        ScanSounds.playFound()
        val qty = _scanMultiplier.value.coerceAtLeast(1)
        channelsRepo.addProductToActive(
            productId = product.product.id,
            barcode = product.product.barcode,
            categoryName = product.category.name,
            priceCents = product.product.priceCents,
            categoryNameEn = product.category.nameEn,
            nameEn = product.product.nameEn,
            sageArticleCode = product.category.sageArticleCode,
            quantity = qty
        )
        _lastScan.value = LastScan(
            productId = product.product.id,
            barcode = product.product.barcode,
            categoryName = product.category.name,
            priceCents = product.product.priceCents,
            quantity = qty,
            categoryNameEn = product.category.nameEn,
            nameEn = product.product.nameEn,
            sageArticleCode = product.category.sageArticleCode,
        )
        // Le multiplicateur reste tel quel après le scan — l'utilisateur le
        // remet à 1 lui-même quand il a terminé le lot.
    }

    /**
     * Create a new category on the fly from the "unknown product" sheet.
     * Returns the id of the created OR existing category (unicité par nom) so
     * le caller peut la sélectionner directement sans que l'utilisateur ait à
     * recommencer sa saisie.
     */
    fun createCategory(
        name: String,
        nameEn: String?,
        sageArticleCode: String?,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val cleaned = name.trim()
            if (cleaned.isEmpty()) return@launch
            val existing = db.categoryDao().findByName(cleaned)
            if (existing != null) {
                onCreated(existing.id)
                return@launch
            }
            val category = Category(
                name = cleaned,
                nameEn = nameEn?.trim()?.takeIf { it.isNotEmpty() },
                sageArticleCode = sageArticleCode?.trim()?.takeIf { it.isNotEmpty() },
            )
            runCatching { db.categoryDao().save(category) }.getOrNull() ?: return@launch
            onCreated(category.id)
        }
    }

    fun dismissUnknown() { _unknownBarcode.value = null }
    fun clearPrintResult() { _printResult.value = null }
    fun clearExportResult() { _exportResult.value = null }

    /** Factures provisoires Sage proposées pour « Ajouter » / « Remplacer ». */
    sealed class SageInvoices {
        data object Loading : SageInvoices()
        data class Loaded(val list: List<SageInvoiceRef>) : SageInvoices()
        data class Error(val message: String) : SageInvoices()
    }

    private val _sageInvoices = MutableStateFlow<SageInvoices>(SageInvoices.Loading)
    val sageInvoices: StateFlow<SageInvoices> = _sageInvoices.asStateFlow()

    fun loadSageInvoices() {
        _sageInvoices.value = SageInvoices.Loading
        viewModelScope.launch {
            val ctx = ScannerApp.instance.applicationContext
            val settings: AppSettings = settingsStore.settings.first()
            ChannelExporter.listProvisionalInvoices(settings)
                .onSuccess { _sageInvoices.value = SageInvoices.Loaded(it) }
                .onFailure { _sageInvoices.value = SageInvoices.Error(it.toHumanMessage(ctx)) }
        }
    }

    fun exportChannel(
        id: String,
        format: ExportFormat,
        sageMode: SageMode = SageMode.CREER,
        sageNumero: String? = null,
    ) {
        viewModelScope.launch {
            val ctx = ScannerApp.instance.applicationContext
            val st = channelsRepo.current()
            val channel = st.channels.firstOrNull { it.id == id } ?: return@launch
            if (channel.lines.isEmpty()) {
                _exportResult.value = PrintResult(false, ctx.getString(R.string.checkout_cart_empty))
                return@launch
            }
            val settings: AppSettings = settingsStore.settings.first()
            _isExporting.value = true
            val result = ChannelExporter.exportChannel(settings, channel, format, sageMode, sageNumero)
            _isExporting.value = false
            result.onSuccess { success ->
                val msg = when (success) {
                    is com.belijolie.scanner.export.ExportSuccess.File ->
                        ctx.getString(R.string.checkout_export_sent, success.filename)
                    is com.belijolie.scanner.export.ExportSuccess.SageInvoice -> when (success.mode) {
                        SageMode.CREER -> ctx.getString(R.string.checkout_export_sage_created, success.numero)
                        SageMode.AJOUTER -> ctx.getString(R.string.checkout_export_sage_added, success.numero)
                        SageMode.REMPLACER -> ctx.getString(R.string.checkout_export_sage_replaced, success.numero)
                    }
                }
                _exportResult.value = PrintResult(true, msg)
            }.onFailure { e ->
                _exportResult.value = PrintResult(false, e.toHumanMessage(ctx))
            }
        }
    }

    /**
     * Create a brand new product with the given barcode / category / price,
     * then add it to the active channel. Called from the "unknown product"
     * sheet when the user picks "Create". Si un produit avec ce code-barre
     * existe déjà, on le prend tel quel (pas de doublon ni d'erreur).
     */
    fun createAndAddToCart(barcode: String, categoryId: String, priceCents: Int) {
        viewModelScope.launch {
            val cleanedBarcode = barcode.trim()
            if (cleanedBarcode.isEmpty()) return@launch

            // Barcode unique au niveau DB → si déjà présent, on réutilise.
            val existing = db.productDao().findByBarcode(cleanedBarcode)
            if (existing != null) {
                channelsRepo.addProductToActive(
                    productId = existing.product.id,
                    barcode = existing.product.barcode,
                    categoryName = existing.category.name,
                    priceCents = existing.product.priceCents,
                    categoryNameEn = existing.category.nameEn,
                    nameEn = existing.product.nameEn,
                    sageArticleCode = existing.category.sageArticleCode
                )
                _unknownBarcode.value = null
                return@launch
            }

            val newProduct = Product(barcode = cleanedBarcode, categoryId = categoryId, priceCents = priceCents)
            runCatching { db.productDao().save(newProduct) }.getOrElse {
                _printResult.value = PrintResult(false, "Impossible de créer le produit.")
                return@launch
            }
            val id = newProduct.id
            val category = db.categoryDao().getById(categoryId) ?: return@launch
            channelsRepo.addProductToActive(
                productId = id,
                barcode = cleanedBarcode,
                categoryName = category.name,
                priceCents = priceCents,
                categoryNameEn = category.nameEn,
                sageArticleCode = category.sageArticleCode
            )
            _lastScan.value = LastScan(
                productId = id,
                barcode = cleanedBarcode,
                categoryName = category.name,
                priceCents = priceCents,
                quantity = 1,
                categoryNameEn = category.nameEn,
                sageArticleCode = category.sageArticleCode,
            )
            _unknownBarcode.value = null
        }
    }

    /**
     * Add an already-existing product to the active channel (does NOT touch
     * the database — the scanned barcode stays unassociated). Called from
     * the "unknown product" sheet when the user picks "Choose from list".
     */
    fun addExistingToCart(productId: String) {
        viewModelScope.launch {
            val product = existingProducts.value.firstOrNull { it.product.id == productId } ?: return@launch
            channelsRepo.addProductToActive(
                productId = product.product.id,
                barcode = product.product.barcode,
                categoryName = product.category.name,
                priceCents = product.product.priceCents,
                categoryNameEn = product.category.nameEn,
                nameEn = product.product.nameEn,
                sageArticleCode = product.category.sageArticleCode
            )
            _lastScan.value = LastScan(
                productId = product.product.id,
                barcode = product.product.barcode,
                categoryName = product.category.name,
                priceCents = product.product.priceCents,
                quantity = 1,
                categoryNameEn = product.category.nameEn,
                nameEn = product.product.nameEn,
                sageArticleCode = product.category.sageArticleCode,
            )
            _unknownBarcode.value = null
        }
    }

    /**
     * Crée un nouveau canal et retourne son id. Utilisé par l'écran liste
     * pour chaîner création + navigation vers le détail.
     */
    suspend fun newChannelAndGetId(): String {
        val index = channelsRepo.current().channels.size + 1
        val channel = channelsRepo.createChannel("Client $index")
        _lastScan.value = null
        return channel.id
    }

    fun newChannel() {
        viewModelScope.launch {
            val index = channelsRepo.current().channels.size + 1
            channelsRepo.createChannel("Client $index")
            // Nouveau canal → le dernier scan précédent n'a plus de sens.
            _lastScan.value = null
        }
    }

    fun selectChannel(id: String) {
        // Positionné en synchrone (hors coroutine) pour qu'un scan arrivé
        // juste après la navigation voit déjà le bon expectedChannelId.
        expectedChannelId = id
        viewModelScope.launch {
            channelsRepo.setActive(id)
            // Changement de canal → l'historique du dernier scan appartient
            // à l'autre canal, on efface pour éviter une édition erronée.
            _lastScan.value = null
        }
    }

    /**
     * Désactive le canal courant (plus aucun canal « actif »). Appelé à
     * l'entrée de l'écran liste des canaux : un scan de produit depuis la
     * liste ne doit alimenter aucun panier.
     */
    fun clearActiveChannel() {
        expectedChannelId = null
        viewModelScope.launch {
            channelsRepo.clearActive()
            _lastScan.value = null
        }
    }

    fun setDiscount(percent: Int) {
        viewModelScope.launch { channelsRepo.setDiscountInActive(percent) }
    }

    /**
     * Édition rapide du dernier scan : annule l'ajout précédent (en
     * décrémentant la quantité de la ligne match avec les anciennes valeurs,
     * voire en supprimant la ligne) puis rejoue avec les nouvelles valeurs.
     *
     * Si la ligne originale n'existe plus (ex : supprimée manuellement par
     * la poubelle entre-temps), on skip l'annulation et on ajoute juste la
     * nouvelle version.
     */
    fun editLastScan(newCategoryName: String, newPriceCents: Int, newQuantity: Int) {
        val last = _lastScan.value ?: return
        val cleanedCat = newCategoryName.trim().ifBlank { last.categoryName }
        val qty = newQuantity.coerceAtLeast(1)
        viewModelScope.launch {
            val st = channelsRepo.current()
            val channel = st.channels.firstOrNull { it.id == st.activeChannelId } ?: return@launch
            // 1. Annule l'ancien scan en retirant last.quantity de la ligne
            //    match (categoryName + priceCents).
            val oldLine = channel.lines.firstOrNull {
                it.categoryName == last.categoryName && it.unitPriceCents == last.priceCents
            }
            if (oldLine != null) {
                channelsRepo.setLineQuantityInActive(
                    oldLine.key,
                    oldLine.quantity - last.quantity,
                )
            }
            // 2. Récupère les métadonnées EN / Sage de la nouvelle catégorie
            //    si elle existe en DB (sinon null).
            val newCat = db.categoryDao().findByName(cleanedCat)
            channelsRepo.addProductToActive(
                productId = last.productId,
                barcode = last.barcode,
                categoryName = cleanedCat,
                priceCents = newPriceCents,
                categoryNameEn = newCat?.nameEn,
                nameEn = last.nameEn,
                sageArticleCode = newCat?.sageArticleCode,
                quantity = qty,
            )
            _lastScan.value = last.copy(
                categoryName = cleanedCat,
                priceCents = newPriceCents,
                quantity = qty,
                categoryNameEn = newCat?.nameEn,
                sageArticleCode = newCat?.sageArticleCode,
            )
        }
    }

    fun renameChannel(id: String, newName: String) {
        viewModelScope.launch { channelsRepo.rename(id, newName) }
    }

    fun closeChannel(id: String) {
        viewModelScope.launch { channelsRepo.closeChannel(id) }
    }

    /** Ticket de caisse en PDF envoyé au PC (dossier d'export du pc-listener). */
    fun exportTicketPdf(id: String, lang: TicketLang) {
        viewModelScope.launch {
            val ctx = ScannerApp.instance.applicationContext
            val channel = channelsRepo.current().channels.firstOrNull { it.id == id } ?: return@launch
            if (channel.lines.isEmpty()) {
                _exportResult.value = PrintResult(false, ctx.getString(R.string.checkout_cart_empty))
                return@launch
            }
            val settings: AppSettings = settingsStore.settings.first()
            _isExporting.value = true
            val result = ChannelExporter.exportTicketPdf(settings, channel, lang)
            _isExporting.value = false
            result.onSuccess { success ->
                val name = (success as? com.belijolie.scanner.export.ExportSuccess.File)?.filename.orEmpty()
                _exportResult.value = PrintResult(true, ctx.getString(R.string.checkout_export_sent, name))
            }.onFailure { e ->
                _exportResult.value = PrintResult(false, e.toHumanMessage(ctx))
            }
        }
    }

    /** Suppression groupée depuis le mode sélection de la liste des canaux. */
    fun closeChannels(ids: Set<String>) {
        viewModelScope.launch { channelsRepo.closeChannels(ids) }
    }

    fun removeLine(key: String) {
        viewModelScope.launch { channelsRepo.removeLineFromActive(key) }
    }

    /**
     * Édite une ligne arbitraire du panier (click direct sur une ligne).
     * Les métadonnées EN / Sage sont récupérées depuis la DB si la nouvelle
     * catégorie y existe ; sinon on garde les snapshots existants.
     */
    fun editLine(key: String, newCategoryName: String, newPriceCents: Int, newQuantity: Int) {
        val cleaned = newCategoryName.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            val newCat = db.categoryDao().findByName(cleaned)
            channelsRepo.editLineInActive(
                key = key,
                newCategoryName = cleaned,
                newPriceCents = newPriceCents,
                newQuantity = newQuantity.coerceAtLeast(1),
                newCategoryNameEn = newCat?.nameEn,
                newSageArticleCode = newCat?.sageArticleCode,
            )
        }
    }

    /**
     * Manually add a line to the active channel (no DB product, no barcode).
     * Merges with an existing line if (category, price) matches.
     *
     * Si [nameEn] est fourni il surcharge toute traduction venant de la DB.
     * Sinon on tente de retrouver la catégorie par nom pour récupérer son
     * nameEn (utile pour l'impression ticket EN).
     *
     * Volontairement : pas de sageArticleCode stocké pour les lignes manuelles —
     * à l'export Sage, elles tombent sur settings.sageManualArticleCode
     * (détecté via barcode vide).
     */
    fun addManualToCart(
        categoryName: String,
        priceCents: Int,
        quantity: Int = 1,
        nameEn: String? = null,
    ) {
        viewModelScope.launch {
            val st = channelsRepo.current()
            if (st.activeChannelId == null) {
                channelsRepo.createChannel("Client 1")
            }
            val cleanedCat = categoryName.trim()
            val cleanedNameEn = nameEn?.trim()?.takeIf { it.isNotEmpty() }
            // Match normalisé (ignore accents + espaces + casse) : si le nom
            // tapé par l'utilisateur correspond exactement à une catégorie
            // existante, on hérite de son nameEn ET surtout de son
            // sageArticleCode. L'export Sage utilisera alors ce code au lieu
            // du settings.sageManualArticleCode fallback.
            val matched = db.categoryDao().getAll().firstOrNull {
                normalizeForMatch(it.name) == normalizeForMatch(cleanedCat)
            }
            channelsRepo.addProductToActive(
                productId = null,
                barcode = "",
                categoryName = cleanedCat,
                priceCents = priceCents,
                categoryNameEn = matched?.nameEn,
                nameEn = cleanedNameEn,
                sageArticleCode = matched?.sageArticleCode,
                quantity = quantity,
            )
            ScanSounds.playFound()
            _lastScan.value = LastScan(
                productId = null,
                barcode = "",
                categoryName = cleanedCat,
                priceCents = priceCents,
                quantity = quantity,
                categoryNameEn = matched?.nameEn,
                nameEn = cleanedNameEn,
                sageArticleCode = matched?.sageArticleCode,
            )
        }
    }

    fun printActive(lang: TicketLang = TicketLang.FR) {
        viewModelScope.launch {
            val ctx = ScannerApp.instance.applicationContext
            val st = channelsRepo.current()
            val active = st.channels.firstOrNull { it.id == st.activeChannelId } ?: return@launch
            if (active.lines.isEmpty()) {
                _printResult.value = PrintResult(false, ctx.getString(R.string.checkout_cart_empty))
                return@launch
            }
            val settings: AppSettings = settingsStore.settings.first()
            _isPrinting.value = true
            val result = runCatching {
                EscPosPrinter.printReceipt(
                    settings = settings,
                    lines = sortLines(active.lines),
                    lang = lang,
                    discountPercent = active.discountPercent
                )
            }
            _isPrinting.value = false
            if (result.isSuccess) {
                // Ticket imprimé → le dernier scan n'est plus modifiable.
                _lastScan.value = null
                _printResult.value = PrintResult(true, ctx.getString(R.string.checkout_ticket_printed))
            } else {
                val msg = result.exceptionOrNull()?.toHumanMessage(ctx)
                    ?: ctx.getString(R.string.common_error)
                _printResult.value = PrintResult(false, msg)
            }
        }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = ScannerApp.instance
            @Suppress("UNCHECKED_CAST")
            return CheckoutViewModel(app.channelsRepo, app.settingsStore, app.db, app.session) as T
        }
    }
}

/**
 * Normalise une chaîne pour comparaison de nom de catégorie : supprime les
 * diacritiques (é → e, à → a…), retire tous les espaces, met en minuscules.
 * "Boucles d'oreilles" et "bouclesdoreilles" matchent.
 */
internal fun normalizeForMatch(s: String): String {
    val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("\\s+"), "")
        .lowercase()
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
