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
    val lines: List<CartLine> = emptyList(),
    // Remise en pourcentage (0-100). Ne concerne QUE l'affichage caisse et le
    // ticket imprimé. L'export CSV / JSON / Sage reste sur les prix bruts
    // (décision produit : la remise est une faveur à la caisse, pas une
    // réalité comptable). Défaut 0 → compat sérialisation canaux existants.
    val discountPercent: Int = 0
) {
    val totalCents: Int get() = lines.sumOf { it.unitPriceCents * it.quantity }

    /** Montant économisé par la remise (en centimes). */
    val discountCents: Int get() =
        if (discountPercent <= 0) 0 else Math.round(totalCents * discountPercent / 100.0).toInt()

    /** Total HT après remise. Égal à [totalCents] si [discountPercent] = 0. */
    val totalCentsAfterDiscount: Int get() = totalCents - discountCents

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
            // Évite les collisions de productId quand une ligne existante avec
            // ce même id a été éditée pour avoir une autre (cat, prix) : si on
            // rescanne le produit original, on crée une 2e ligne avec le même
            // productId → crash LazyColumn ("Key already used"). Dans ce cas
            // on alloue un id négatif unique à la nouvelle ligne (comme pour
            // les produits manuels), elle reste liée à la DB via le barcode.
            val safeId = if (lines.any { it.productId == productId })
                -System.nanoTime()
            else productId
            lines + CartLine(
                productId = safeId,
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

    /**
     * Positionne la quantité absolue d'une ligne. Si [quantity] ≤ 0 la ligne
     * est retirée. Utilisé pour "annuler le dernier scan" en soustrayant la
     * quantité ajoutée au scan précédent.
     */
    fun setLineQuantity(productId: Long, quantity: Int): Channel {
        if (quantity <= 0) return removeLine(productId)
        return copy(lines = lines.map {
            if (it.productId == productId) it.copy(quantity = quantity) else it
        })
    }

    /**
     * Édite une ligne du panier (depuis un click sur la ligne). Si la nouvelle
     * (catégorie, prix) match une autre ligne existante, les deux fusionnent
     * et la ligne originale disparaît. Sinon on remplace sur place.
     */
    fun editLine(
        productId: Long,
        newCategoryName: String,
        newPriceCents: Int,
        newQuantity: Int,
        newCategoryNameEn: String? = null,
        newSageArticleCode: String? = null,
    ): Channel {
        val qty = newQuantity.coerceAtLeast(1)
        val targetLine = lines.firstOrNull { it.productId == productId } ?: return this
        val existingOther = lines.firstOrNull {
            it.productId != productId &&
                it.categoryName == newCategoryName &&
                it.unitPriceCents == newPriceCents
        }
        val newLines = if (existingOther != null) {
            lines
                .filter { it.productId != productId }
                .map {
                    if (it.productId == existingOther.productId)
                        it.copy(quantity = it.quantity + qty)
                    else it
                }
        } else {
            lines.map {
                if (it.productId == productId) it.copy(
                    categoryName = newCategoryName,
                    unitPriceCents = newPriceCents,
                    quantity = qty,
                    categoryNameEn = newCategoryNameEn ?: targetLine.categoryNameEn,
                    sageArticleCode = newSageArticleCode ?: targetLine.sageArticleCode,
                ) else it
            }
        }
        return copy(lines = newLines)
    }

    fun rename(newName: String): Channel = copy(name = newName)

    fun setDiscount(percent: Int): Channel = copy(discountPercent = percent.coerceIn(0, 100))
}

@Serializable
data class ChannelsState(
    val channels: List<Channel> = emptyList(),
    val activeChannelId: String? = null,
    val nextTicketNumber: Int = 1
)
