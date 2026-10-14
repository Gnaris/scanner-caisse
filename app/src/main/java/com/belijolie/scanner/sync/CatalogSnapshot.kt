package com.belijolie.scanner.sync

import kotlinx.serialization.Serializable

/**
 * Snapshot sérialisable du catalogue d'un PDA à un instant T.
 *
 * Utilisé par la synchronisation QR code entre PDAs : l'appareil source
 * sérialise ce snapshot → gzip → base64 → découpage en segments QR ; l'appareil
 * destination reconstitue le snapshot et merge dans sa propre base.
 *
 * [version] est bump à chaque changement incompatible du schéma. Un snapshot
 * d'une version plus récente est refusé par les anciennes versions de l'app.
 */
@Serializable
data class CatalogSnapshot(
    val version: Int = 1,
    val categories: List<CategoryDto>,
    val products: List<ProductDto>,
    val settings: SettingsDto,
)

@Serializable
data class CategoryDto(
    val name: String,
    val nameEn: String? = null,
    val sageArticleCode: String? = null,
    val barcodePrefix: String? = null,
)

/**
 * Produit sans FK vers Category : on utilise [categoryName] comme pivot, résolu
 * en categoryId local au moment de l'import. Ça permet aux id autogen de
 * différer entre PDAs sans casser la synchro.
 */
@Serializable
data class ProductDto(
    val barcode: String,
    val categoryName: String,
    val priceCents: Int,
    val nameEn: String? = null,
)

/**
 * Sous-ensemble synchronisable des paramètres : on exporte uniquement ce qui
 * est **lié au commerce** (identité, QR branding, code Sage manuel) et pas la
 * config matérielle propre à chaque PDA (IP imprimante, URL export PC,
 * action/extra broadcast scanner).
 */
@Serializable
data class SettingsDto(
    val shopName: String,
    val address: String,
    val addressComplement: String,
    val postalCode: String,
    val city: String,
    val country: String,
    val microstoreQr: String,
    val microstoreCode: String,
    val websiteUrl: String,
    val googleReviewUrl: String,
    val sageManualArticleCode: String,
)
