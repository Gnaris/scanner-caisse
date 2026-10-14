package com.belijolie.scanner.util

import com.belijolie.scanner.data.entity.Category

/**
 * Scanne les codes articles Sage déjà pris sur les catégories existantes et
 * renvoie le prochain `ART000X` libre. Les codes qui ne matchent pas le
 * pattern (saisis à la main différemment par l'utilisateur) sont ignorés.
 * Format sur 4 chiffres : ART0001, ART0002, …, ART9999.
 */
fun suggestNextSageCode(categories: List<Category>): String {
    val pattern = Regex("^ART(\\d+)$")
    val used = categories
        .mapNotNull { it.sageArticleCode?.trim() }
        .mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        .toSet()
    var n = 1
    while (n in used) n++
    return "ART%04d".format(n)
}
