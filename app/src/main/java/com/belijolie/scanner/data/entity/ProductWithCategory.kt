package com.belijolie.scanner.data.entity

import androidx.room.Embedded
import androidx.room.Relation

data class ProductWithCategory(
    @Embedded val product: Product,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: Category
)
