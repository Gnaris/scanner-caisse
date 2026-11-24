package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.belijolie.scanner.data.newId
import com.belijolie.scanner.data.nowMs

@Entity(
    tableName = "products",
    // Pas de clé étrangère ni d'index UNIQUE : les données reçues du serveur
    // arrivent dans n'importe quel ordre, et l'unicité du code-barres est
    // vérifiée par le code + garantie entre appareils par le serveur.
    indices = [Index(value = ["barcode"]), Index("categoryId")]
)
data class Product(
    @PrimaryKey val id: String = newId(),
    val barcode: String,
    val categoryId: String,
    val priceCents: Int,
    // Nom anglais optionnel pour les produits manuels. Pour les produits
    // scannés, le nom affiché/imprimé reste celui de la catégorie
    // (resp. categoryName / category.nameEn selon la langue du ticket).
    val nameEn: String? = null,
    val updatedAt: Long = nowMs(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)
