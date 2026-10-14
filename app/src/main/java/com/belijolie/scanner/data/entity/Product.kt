package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [Index(value = ["barcode"], unique = true), Index("categoryId")],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val categoryId: Long,
    val priceCents: Int,
    // Nom anglais optionnel pour les produits manuels. Pour les produits
    // scannés, le nom affiché/imprimé reste celui de la catégorie
    // (resp. categoryName / category.nameEn selon la langue du ticket).
    val nameEn: String? = null
)
