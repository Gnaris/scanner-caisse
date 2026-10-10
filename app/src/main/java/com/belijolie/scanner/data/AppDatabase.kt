package com.belijolie.scanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.belijolie.scanner.data.dao.CategoryDao
import com.belijolie.scanner.data.dao.ProductDao
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.Product

@Database(
    entities = [Category::class, Product::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scanner.db"
                ).build().also { instance = it }
            }
    }
}
