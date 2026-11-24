package com.belijolie.scanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.belijolie.scanner.data.dao.CategoryDao
import com.belijolie.scanner.data.dao.ChannelDao
import com.belijolie.scanner.data.dao.DeviceDao
import com.belijolie.scanner.data.dao.ProductDao
import com.belijolie.scanner.data.dao.SettingDao
import com.belijolie.scanner.data.dao.SyncDao
import com.belijolie.scanner.data.entity.CartLineEntity
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ChannelEntity
import com.belijolie.scanner.data.entity.Device
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.SharedSetting

@Database(
    entities = [
        Category::class,
        Product::class,
        Device::class,
        ChannelEntity::class,
        CartLineEntity::class,
        SharedSetting::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun productDao(): ProductDao
    abstract fun deviceDao(): DeviceDao
    abstract fun channelDao(): ChannelDao
    abstract fun settingDao(): SettingDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v5 → v6 : ajout du champ `barcodePrefix` sur les catégories.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN barcodePrefix TEXT")
            }
        }

        /** Expression SQLite qui produit un UUID v4 aléatoire (texte). */
        private const val UUID_SQL =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "lower(hex(randomblob(6)))"

        /**
         * v6 → v7 : passage à la synchronisation centrale.
         *  - Identifiants numériques → UUID texte (sinon deux PDA hors-ligne
         *    créent chacun un « produit n°42 » et entrent en collision).
         *  - Colonnes de synchro (updatedAt, deleted, dirty) partout.
         *  - Plus d'index UNIQUE ni de clé étrangère (voir Category / Product).
         *  - Nouvelles tables : appareils, canaux, lignes de panier, paramètres
         *    partagés. Les canaux et paramètres existants (DataStore) sont
         *    recopiés au démarrage par LegacyImporter.
         *
         * Les lignes reprises ont updatedAt = 0 : n'importe quelle vraie
         * modification faite ensuite sur un autre appareil l'emporte.
         * Elles sont marquées dirty pour être envoyées au premier envoi.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `_cat_map` (`oldId` INTEGER NOT NULL PRIMARY KEY, `newId` TEXT NOT NULL)")
                db.execSQL("INSERT INTO `_cat_map` (oldId, newId) SELECT id, $UUID_SQL FROM categories")

                db.execSQL(CREATE_CATEGORIES.replace("`categories`", "`categories_new`"))
                db.execSQL(
                    """
                    INSERT INTO categories_new (id, name, nameEn, sageArticleCode, barcodePrefix, updatedAt, deleted, dirty)
                    SELECT m.newId, c.name, c.nameEn, c.sageArticleCode, c.barcodePrefix, 0, 0, 1
                    FROM categories c INNER JOIN _cat_map m ON m.oldId = c.id
                    """.trimIndent()
                )

                db.execSQL(CREATE_PRODUCTS.replace("`products`", "`products_new`"))
                db.execSQL(
                    """
                    INSERT INTO products_new (id, barcode, categoryId, priceCents, nameEn, updatedAt, deleted, dirty)
                    SELECT $UUID_SQL, p.barcode, m.newId, p.priceCents, p.nameEn, 0, 0, 1
                    FROM products p INNER JOIN _cat_map m ON m.oldId = p.categoryId
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE products")
                db.execSQL("DROP TABLE categories")
                db.execSQL("DROP TABLE _cat_map")
                db.execSQL("ALTER TABLE categories_new RENAME TO categories")
                db.execSQL("ALTER TABLE products_new RENAME TO products")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_categoryId` ON `products` (`categoryId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `devices` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `channels` (`id` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `discountPercent` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_deviceId` ON `channels` (`deviceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cart_lines` (`id` TEXT NOT NULL, `channelId` TEXT NOT NULL, " +
                        "`productId` TEXT, `barcode` TEXT NOT NULL, `categoryName` TEXT NOT NULL, " +
                        "`categoryNameEn` TEXT, `nameEn` TEXT, `sageArticleCode` TEXT, " +
                        "`unitPriceCents` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cart_lines_channelId` ON `cart_lines` (`channelId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `settings` (`id` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        private const val CREATE_CATEGORIES =
            "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`nameEn` TEXT, `sageArticleCode` TEXT, `barcodePrefix` TEXT, `updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        private const val CREATE_PRODUCTS =
            "CREATE TABLE IF NOT EXISTS `products` (`id` TEXT NOT NULL, `barcode` TEXT NOT NULL, " +
                "`categoryId` TEXT NOT NULL, `priceCents` INTEGER NOT NULL, `nameEn` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scanner.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    // Versions < 5 sans migration déclarée : destructive
                    // (très anciennes installs, catalogue reconstructible).
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                    .build().also { instance = it }
            }
    }
}
