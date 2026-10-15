package com.belijolie.scanner.printer

/**
 * Langue d'impression du ticket. Indépendante de la langue de l'UI :
 * un commerce francophone peut imprimer un ticket EN pour un client
 * anglophone sans changer sa propre langue d'interface.
 */
enum class TicketLang { FR, EN }

/**
 * Tous les libellés localisés qui apparaissent sur le ticket.
 * Centralisés ici pour que TicketPreviewSheet et EscPosPrinter restent synchro
 * (même source de vérité pour les deux rendus).
 */
data class TicketLabels(
    val headerCategory: String,
    val headerQty: String,
    val headerUnit: String,
    val headerNet: String,
    val totalQuantity: String,
    val totalNet: String,
    val totalNetAfter: String,
    val totalVat: String,
    val totalGross: String,
    val discountLabel: String,
    val microstore: String,
    val onlineShop: String,
    val googleReview: String,
    val reviewTitle: String,
    val reviewSubtitle: String
) {
    companion object {
        val FR = TicketLabels(
            headerCategory = "Catégorie",
            headerQty = "Qt",
            headerUnit = "P.U.",
            headerNet = "HT",
            totalQuantity = "Quantité totale",
            totalNet = "Total HT",
            totalNetAfter = "Total HT remisé",
            totalVat = "TVA totale (20%)",
            totalGross = "Total TTC",
            discountLabel = "Remise",
            microstore = "MICROSTORE",
            onlineShop = "BOUTIQUE EN LIGNE",
            googleReview = "AVIS GOOGLE",
            reviewTitle = "LAISSEZ UN AVIS !",
            reviewSubtitle = "Scannez pour nous noter sur Google"
        )

        val EN = TicketLabels(
            headerCategory = "Item",
            headerQty = "Qty",
            headerUnit = "Unit",
            headerNet = "Net",
            totalQuantity = "Total quantity",
            totalNet = "Total net",
            totalNetAfter = "Net after discount",
            totalVat = "Total VAT (20%)",
            totalGross = "Total gross",
            discountLabel = "Discount",
            microstore = "MICROSTORE",
            onlineShop = "ONLINE SHOP",
            googleReview = "GOOGLE REVIEWS",
            reviewTitle = "LEAVE US A REVIEW!",
            reviewSubtitle = "Scan to rate us on Google"
        )

        fun of(lang: TicketLang): TicketLabels = when (lang) {
            TicketLang.FR -> FR
            TicketLang.EN -> EN
        }
    }
}

/**
 * Résout le nom à afficher sur le ticket pour une ligne, selon la langue.
 *
 * En FR → `categoryName` (le nom tapé par l'utilisateur, sa langue naturelle).
 * En EN → cascade : `nameEn` (surcharge produit manuel) → `categoryNameEn`
 * (traduction catégorie) → fallback sur `categoryName` si rien d'autre.
 */
fun resolveLineDisplayName(
    categoryName: String,
    categoryNameEn: String?,
    nameEn: String?,
    lang: TicketLang
): String = when (lang) {
    TicketLang.FR -> categoryName
    TicketLang.EN -> nameEn?.takeIf { it.isNotBlank() }
        ?: categoryNameEn?.takeIf { it.isNotBlank() }
        ?: categoryName
}
