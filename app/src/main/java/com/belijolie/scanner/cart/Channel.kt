package com.belijolie.scanner.cart

import com.belijolie.scanner.data.entity.CartLineEntity

/**
 * Ligne AFFICHÉE du panier : regroupement de tous les ajouts
 * ([CartLineEntity]) qui ont la même catégorie et le même prix unitaire.
 * Les quantités sont additionnées.
 *
 * [key] identifie le regroupement (catégorie + prix) et sert de clé pour
 * supprimer / modifier la ligne depuis l'UI.
 */
data class CartLine(
    val key: String,
    // Produit du catalogue du premier ajout (null pour un ajout manuel).
    val productId: String?,
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

data class Channel(
    val id: String,
    val name: String,
    val lines: List<CartLine> = emptyList(),
    // Remise en pourcentage (0-100). Ne concerne QUE l'affichage caisse et le
    // ticket imprimé. L'export CSV / JSON / Sage reste sur les prix bruts
    // (décision produit : la remise est une faveur à la caisse, pas une
    // réalité comptable).
    val discountPercent: Int = 0
) {
    val totalCents: Int get() = lines.sumOf { it.unitPriceCents * it.quantity }

    /** Montant économisé par la remise (en centimes). */
    val discountCents: Int get() =
        if (discountPercent <= 0) 0 else Math.round(totalCents * discountPercent / 100.0).toInt()

    /** Total HT après remise. Égal à [totalCents] si [discountPercent] = 0. */
    val totalCentsAfterDiscount: Int get() = totalCents - discountCents
}

data class ChannelsState(
    val channels: List<Channel> = emptyList(),
    val activeChannelId: String? = null,
)

/** Clé de regroupement d'un ajout : même catégorie + même prix = même ligne. */
fun lineKey(categoryName: String, unitPriceCents: Int): String =
    "$categoryName\u0000$unitPriceCents"

/**
 * Regroupe les ajouts d'un canal en lignes affichées. Les lignes gardent
 * l'ordre de leur premier ajout. Pour les champs « snapshot » (EN, Sage), on
 * prend la dernière valeur non nulle : utile quand une catégorie a été
 * enrichie en base entre deux scans.
 */
fun groupLines(rows: List<CartLineEntity>): List<CartLine> =
    rows.sortedBy { it.createdAt }
        .groupBy { lineKey(it.categoryName, it.unitPriceCents) }
        .map { (key, group) ->
            val first = group.first()
            CartLine(
                key = key,
                productId = group.firstNotNullOfOrNull { it.productId },
                barcode = first.barcode,
                categoryName = first.categoryName,
                unitPriceCents = first.unitPriceCents,
                quantity = group.sumOf { it.quantity },
                categoryNameEn = group.lastOrNull { it.categoryNameEn != null }?.categoryNameEn,
                nameEn = group.lastOrNull { it.nameEn != null }?.nameEn,
                sageArticleCode = group.lastOrNull { it.sageArticleCode != null }?.sageArticleCode,
            )
        }
