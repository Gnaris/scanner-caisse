package com.belijolie.scanner.sync

import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.data.SettingsStore
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.Product
import kotlinx.coroutines.flow.first

/**
 * Pivot entre la DB locale et un [CatalogSnapshot] sérialisable.
 *
 *  - [exportSnapshot] : lit tout le catalogue + le sous-ensemble synchronisable
 *    des paramètres, renvoie un [CatalogSnapshot] prêt pour l'encodage QR.
 *  - [importSnapshot] : merge dans la DB existante. Catégories matchées par
 *    nom (NOCASE), produits par code-barres. Les paramètres synchronisables
 *    sont écrasés (identité du commerce), le matériel reste local.
 */
class SyncManager(
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
) {

    suspend fun exportSnapshot(): CatalogSnapshot {
        val categories = db.categoryDao().getAll()
        val products = db.productDao().getAll()
        val categoriesById = categories.associateBy { it.id }
        val settings = settingsStore.settings.first()

        return CatalogSnapshot(
            categories = categories.map { c ->
                CategoryDto(
                    name = c.name,
                    nameEn = c.nameEn,
                    sageArticleCode = c.sageArticleCode,
                    barcodePrefix = c.barcodePrefix,
                )
            },
            products = products.mapNotNull { p ->
                val cat = categoriesById[p.categoryId] ?: return@mapNotNull null
                ProductDto(
                    barcode = p.barcode,
                    categoryName = cat.name,
                    priceCents = p.priceCents,
                    nameEn = p.nameEn,
                )
            },
            settings = settings.toSyncDto(),
        )
    }

    suspend fun importSnapshot(snapshot: CatalogSnapshot): ImportResult {
        var categoriesInserted = 0
        var categoriesUpdated = 0
        var productsInserted = 0
        var productsUpdated = 0
        var productsSkipped = 0

        val categoryDao = db.categoryDao()
        val productDao = db.productDao()

        // 1. Catégories : merge par nom (NOCASE).
        for (dto in snapshot.categories) {
            val existing = categoryDao.findByName(dto.name)
            if (existing == null) {
                categoryDao.insert(
                    Category(
                        name = dto.name,
                        nameEn = dto.nameEn?.takeIf { it.isNotBlank() },
                        sageArticleCode = dto.sageArticleCode?.takeIf { it.isNotBlank() },
                        barcodePrefix = dto.barcodePrefix?.takeIf { it.isNotBlank() },
                    )
                )
                categoriesInserted++
            } else {
                categoryDao.update(
                    existing.copy(
                        nameEn = dto.nameEn?.takeIf { it.isNotBlank() } ?: existing.nameEn,
                        sageArticleCode = dto.sageArticleCode?.takeIf { it.isNotBlank() }
                            ?: existing.sageArticleCode,
                        barcodePrefix = dto.barcodePrefix?.takeIf { it.isNotBlank() }
                            ?: existing.barcodePrefix,
                    )
                )
                categoriesUpdated++
            }
        }

        // 2. Produits : merge par barcode. On a besoin du categoryId local
        //    résolu depuis le nom (les ids autogen diffèrent entre PDAs).
        val allCategories = categoryDao.getAll().associateBy { it.name.lowercase() }
        for (dto in snapshot.products) {
            val targetCategory = allCategories[dto.categoryName.lowercase()]
            if (targetCategory == null) {
                productsSkipped++
                continue
            }
            val existing = productDao.findProductByBarcode(dto.barcode)
            if (existing == null) {
                productDao.insert(
                    Product(
                        barcode = dto.barcode,
                        categoryId = targetCategory.id,
                        priceCents = dto.priceCents,
                        nameEn = dto.nameEn?.takeIf { it.isNotBlank() },
                    )
                )
                productsInserted++
            } else {
                productDao.update(
                    existing.copy(
                        categoryId = targetCategory.id,
                        priceCents = dto.priceCents,
                        nameEn = dto.nameEn?.takeIf { it.isNotBlank() } ?: existing.nameEn,
                    )
                )
                productsUpdated++
            }
        }

        // 3. Paramètres : on garde la config matérielle locale (printerIp,
        //    printerPort, scannerAction, scannerExtra, exportUrl) et on
        //    remplace tout le reste par l'import.
        val localSettings = settingsStore.settings.first()
        settingsStore.update(snapshot.settings.applyOnto(localSettings))

        return ImportResult(
            categoriesInserted = categoriesInserted,
            categoriesUpdated = categoriesUpdated,
            productsInserted = productsInserted,
            productsUpdated = productsUpdated,
            productsSkipped = productsSkipped,
        )
    }
}

data class ImportResult(
    val categoriesInserted: Int,
    val categoriesUpdated: Int,
    val productsInserted: Int,
    val productsUpdated: Int,
    val productsSkipped: Int,
)

private fun AppSettings.toSyncDto() = SettingsDto(
    shopName = shopName,
    address = address,
    addressComplement = addressComplement,
    postalCode = postalCode,
    city = city,
    country = country,
    microstoreQr = microstoreQr,
    microstoreCode = microstoreCode,
    websiteUrl = websiteUrl,
    googleReviewUrl = googleReviewUrl,
    sageManualArticleCode = sageManualArticleCode,
)

/** Fusionne la partie synchronisée avec la config matérielle locale préservée. */
private fun SettingsDto.applyOnto(local: AppSettings) = local.copy(
    shopName = shopName,
    address = address,
    addressComplement = addressComplement,
    postalCode = postalCode,
    city = city,
    country = country,
    microstoreQr = microstoreQr,
    microstoreCode = microstoreCode,
    websiteUrl = websiteUrl,
    googleReviewUrl = googleReviewUrl,
    sageManualArticleCode = sageManualArticleCode,
)
