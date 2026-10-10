package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.ProductWithCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Transaction
    @Query("SELECT * FROM products ORDER BY barcode")
    fun observeAllWithCategory(): Flow<List<ProductWithCategory>>

    @Transaction
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): ProductWithCategory?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: Product): Long

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteById(id: Long)
}
