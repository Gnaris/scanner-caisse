package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.ProductWithCategory
import com.belijolie.scanner.data.nowMs
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProductDao {

    // Tri UI : d'abord par nom de catégorie (alphabétique, insensible à la
    // casse), puis par prix croissant à l'intérieur d'une catégorie. Le JOIN
    // garantit aussi que la catégorie existe localement (sinon @Relation
    // planterait sur un produit reçu avant sa catégorie).
    @Transaction
    @Query("""
        SELECT products.* FROM products
        INNER JOIN categories ON categories.id = products.categoryId
        WHERE products.deleted = 0 AND categories.deleted = 0
        ORDER BY categories.name COLLATE NOCASE ASC, products.priceCents ASC
    """)
    abstract fun observeAllWithCategory(): Flow<List<ProductWithCategory>>

    @Transaction
    @Query("""
        SELECT products.* FROM products
        INNER JOIN categories ON categories.id = products.categoryId
        WHERE products.barcode = :barcode AND products.deleted = 0 AND categories.deleted = 0
        LIMIT 1
    """)
    abstract suspend fun findByBarcode(barcode: String): ProductWithCategory?

    @Query("SELECT * FROM products WHERE deleted = 0 ORDER BY barcode")
    abstract suspend fun getAll(): List<Product>

    @Query("SELECT * FROM products WHERE barcode = :barcode AND deleted = 0 LIMIT 1")
    abstract suspend fun findProductByBarcode(barcode: String): Product?

    /** Écriture brute (synchro). Pour une modif utilisateur, passer par [save]. */
    @Upsert
    abstract suspend fun upsertRaw(product: Product)

    /** Enregistre une modification locale : horodatée et marquée « à envoyer ». */
    open suspend fun save(product: Product) {
        upsertRaw(product.copy(updatedAt = nowMs(), dirty = true))
    }

    @Query("UPDATE products SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long = nowMs())
}
