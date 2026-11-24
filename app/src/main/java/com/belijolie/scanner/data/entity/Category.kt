package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.belijolie.scanner.data.newId
import com.belijolie.scanner.data.nowMs

@Entity(
    tableName = "categories",
    // Pas d'index UNIQUE : l'unicité du nom (insensible à la casse) est
    // vérifiée par le code (CategoryDao.findByName) et garantie entre
    // appareils par le serveur, qui fusionne les doublons. Un index unique
    // local bloquerait la recréation d'une catégorie supprimée (ligne
    // conservée avec deleted=1) et l'application des données reçues.
    indices = [Index(value = ["name"])]
)
data class Category(
    @PrimaryKey val id: String = newId(),
    val name: String,
    // Traduction anglaise optionnelle du nom. Utilisée pour imprimer le
    // ticket en EN. Null = retomber sur `name` à l'impression.
    val nameEn: String? = null,
    // Code article Sage 50 propre à cette catégorie (ex. "ART0001").
    // Null → export Sage impossible pour les produits scannés de cette
    // catégorie (le ChannelExporter bloquera avec un message explicite).
    val sageArticleCode: String? = null,
    // Préfixe de code-barres associé à cette catégorie (ex. "22222"). Si un
    // code-barres inconnu scanné commence par ce préfixe, cette catégorie
    // sera pré-sélectionnée dans le modal "créer produit". Facultatif.
    val barcodePrefix: String? = null,
    val updatedAt: Long = nowMs(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)
