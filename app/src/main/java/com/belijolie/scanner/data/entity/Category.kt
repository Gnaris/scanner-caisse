package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    // Index unique insensible à la casse : "Robe" et "robe" ne peuvent pas
    // coexister. Garantit l'unicité au niveau SQLite avant même l'UI.
    indices = [Index(value = ["name"], unique = true)]
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Traduction anglaise optionnelle du nom. Utilisée pour imprimer le
    // ticket en EN. Null = retomber sur `name` à l'impression.
    val nameEn: String? = null,
    // Code article Sage 50 propre à cette catégorie (ex. "ART0001").
    // Facultatif : seuls les utilisateurs qui facturent via Sage le remplissent.
    // Null → export Sage impossible pour les produits scannés de cette catégorie
    // (le ChannelExporter bloquera avec un message d'erreur explicite).
    val sageArticleCode: String? = null,
    // Préfixe de code-barres associé à cette catégorie (ex. "22222"). Si un
    // code-barres inconnu scanné commence par ce préfixe, cette catégorie
    // sera pré-sélectionnée dans le modal "créer produit". Facultatif.
    val barcodePrefix: String? = null
)
