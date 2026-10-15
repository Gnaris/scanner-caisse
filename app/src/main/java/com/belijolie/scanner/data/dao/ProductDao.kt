package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.ProductWithCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    // Tri UI : d'abord par nom de catégorie (alphabétique, insensible à la
    // casse), puis par prix croissant à l'intérieur d'une catégorie. Le JOIN
    // explicite sur categories est nécessaire parce que @Relation fait une
    // 2e requête, donc ORDER BY ne peut pas référencer category.name sans
    // ce JOIN.
    @Transaction
    @Query("""
        SELECT products.* FROM products
        INNER JOIN categories ON categories.id = products.categoryId
        ORDER BY categories.name COLLATE NOCASE ASC, products.priceCents ASC
    """)
    fun observeAllWithCategory(): Flow<List<ProductWithCategory>>

    @Transaction
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): ProductWithCategory?

    @Query("SELECT * FROM products ORDER BY barcode")
    suspend fun getAll(): List<Product>

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findProductByBarcode(barcode: String): Product?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: Product): Long

    @Update
    suspend fun update(product: Product)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteById(id: Long)
}
