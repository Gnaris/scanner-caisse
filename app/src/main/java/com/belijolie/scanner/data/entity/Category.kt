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
    val name: String
)
