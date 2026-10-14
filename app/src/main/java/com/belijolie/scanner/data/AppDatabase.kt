package com.belijolie.scanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.belijolie.scanner.data.dao.CategoryDao
import com.belijolie.scanner.data.dao.ProductDao
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.Product

@Database(
    entities = [Category::class, Product::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v5 → v6 : ajout du champ `barcodePrefix` sur les catégories.
         * Migration non-destructive pour préserver le catalogue existant.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN barcodePrefix TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scanner.db"
                )
                    .addMigrations(MIGRATION_5_6)
                    // Pour les versions antérieures sans migration déclarée,
                    // on tombe en destructive (catalogue reconstructible).
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
