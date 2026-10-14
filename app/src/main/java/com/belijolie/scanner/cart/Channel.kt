package com.belijolie.scanner.cart

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CartLine(
    val productId: Long,
    val barcode: String,
    val categoryName: String,
    val unitPriceCents: Int,
    val quantity: Int,
    // Traduction anglaise de la catégorie (snapshot au moment de l'ajout).
    // Utilisée pour imprimer le ticket en EN. Null → retomber sur categoryName.
    val categoryNameEn: String? = null,
    // Nom spécifique anglais de ce produit (uniquement pour produits manuels
    // avec traduction custom). Surcharge categoryNameEn à l'impression EN.
    val nameEn: String? = null,
    // Code article Sage 50 propre à la catégorie du produit (snapshot).
    // Null pour les produits manuels (= barcode vide) : l'export Sage utilise
    // alors settings.sageManualArticleCode à la place.
    val sageArticleCode: String? = null
)

@Serializable
data class Channel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lines: List<CartLine> = emptyList()
) {
    val totalCents: Int get() = lines.sumOf { it.unitPriceCents * it.quantity }

    /**
     * Merge lines when the (category, unit price) pair is the same — regardless
     * of productId or barcode. This keeps the cart tidy if the same item is
     * deleted and re-created, or if two distinct products share identical
     * category and price.
     */
    fun addProduct(
        productId: Long,
        barcode: String,
        categoryName: String,
        priceCents: Int,
        categoryNameEn: String? = null,
        nameEn: String? = null,
        sageArticleCode: String? = null,
        quantity: Int = 1
    ): Channel {
        val qty = quantity.coerceAtLeast(1)
        val existing = lines.indexOfFirst {
            it.categoryName == categoryName && it.unitPriceCents == priceCents
        }
        val newLines = if (existing >= 0) {
            lines.toMutableList().also {
                // Fusion : incrémente la quantité par [qty] et rafraîchit les
                // champs "snapshot" (EN / sage) si la nouvelle valeur n'est
                // pas null — utile quand on scanne la 1re fois une catégorie
                // qui vient d'être enrichie en DB.
                val current = it[existing]
                it[existing] = current.copy(
                    quantity = current.quantity + qty,
                    categoryNameEn = categoryNameEn ?: current.categoryNameEn,
                    nameEn = nameEn ?: current.nameEn,
                    sageArticleCode = sageArticleCode ?: current.sageArticleCode
                )
            }
        } else {
            lines + CartLine(
                productId = productId,
                barcode = barcode,
                categoryName = categoryName,
                unitPriceCents = priceCents,
                quantity = qty,
                categoryNameEn = categoryNameEn,
                nameEn = nameEn,
                sageArticleCode = sageArticleCode
            )
        }
        return copy(lines = newLines)
    }

    fun removeLine(productId: Long): Channel =
        copy(lines = lines.filterNot { it.productId == productId })

    fun rename(newName: String): Channel = copy(name = newName)
}

@Serializable
data class ChannelsState(
    val channels: List<Channel> = emptyList(),
    val activeChannelId: String? = null,
    val nextTicketNumber: Int = 1
)
