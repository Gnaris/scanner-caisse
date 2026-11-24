package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.nowMs
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CategoryDao {

    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    abstract suspend fun getAll(): List<Category>

    @Query("SELECT * FROM categories WHERE id = :id AND deleted = 0")
    abstract suspend fun getById(id: String): Category?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE AND deleted = 0 LIMIT 1")
    abstract suspend fun findByName(name: String): Category?

    @Query("SELECT COUNT(*) FROM products WHERE categoryId = :categoryId AND deleted = 0")
    abstract suspend fun countProducts(categoryId: String): Int

    /** Écriture brute (synchro). Pour une modif utilisateur, passer par [save]. */
    @Upsert
    abstract suspend fun upsertRaw(category: Category)

    /** Enregistre une modification locale : horodatée et marquée « à envoyer ». */
    open suspend fun save(category: Category) {
        upsertRaw(category.copy(updatedAt = nowMs(), dirty = true))
    }

    @Query("UPDATE categories SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long = nowMs())
}
