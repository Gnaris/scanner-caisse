package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.belijolie.scanner.data.entity.CartLineEntity
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ChannelEntity
import com.belijolie.scanner.data.entity.Device
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.SharedSetting
import kotlinx.coroutines.flow.Flow

/**
 * Requêtes utilisées uniquement par le moteur de synchronisation
 * (SyncEngine). Elles voient aussi les lignes supprimées (deleted = 1).
 */
@Dao
abstract class SyncDao {

    /** Nombre de lignes modifiées localement et pas encore envoyées. */
    @Query("""
        SELECT (SELECT COUNT(*) FROM devices WHERE dirty = 1)
             + (SELECT COUNT(*) FROM categories WHERE dirty = 1)
             + (SELECT COUNT(*) FROM products WHERE dirty = 1)
             + (SELECT COUNT(*) FROM channels WHERE dirty = 1)
             + (SELECT COUNT(*) FROM cart_lines WHERE dirty = 1)
             + (SELECT COUNT(*) FROM settings WHERE dirty = 1)
    """)
    abstract fun observeDirtyCount(): Flow<Int>

    // ── Lignes à envoyer ────────────────────────────────────────────────
    @Query("SELECT * FROM devices WHERE dirty = 1 LIMIT :limit") abstract suspend fun dirtyDevices(limit: Int): List<Device>
    @Query("SELECT * FROM categories WHERE dirty = 1 LIMIT :limit") abstract suspend fun dirtyCategories(limit: Int): List<Category>
    @Query("SELECT * FROM products WHERE dirty = 1 LIMIT :limit") abstract suspend fun dirtyProducts(limit: Int): List<Product>
    @Query("SELECT * FROM channels WHERE dirty = 1 LIMIT :limit") abstract suspend fun dirtyChannels(limit: Int): List<ChannelEntity>
    @Query("SELECT * FROM cart_lines WHERE dirty = 1 LIMIT :limit") abstract suspend fun dirtyCartLines(limit: Int): List<CartLineEntity>
    @Query("SELECT * FROM settings WHERE dirty = 1 LIMIT :limit") abstract suspend fun dirtySettings(limit: Int): List<SharedSetting>

    // ── Envoi confirmé : plus « à envoyer », sauf si modifiée entre-temps ──
    @Query("UPDATE devices SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun cleanDevice(id: String, updatedAt: Long)
    @Query("UPDATE categories SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun cleanCategory(id: String, updatedAt: Long)
    @Query("UPDATE products SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun cleanProduct(id: String, updatedAt: Long)
    @Query("UPDATE channels SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun cleanChannel(id: String, updatedAt: Long)
    @Query("UPDATE cart_lines SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun cleanCartLine(id: String, updatedAt: Long)
    @Query("UPDATE settings SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun cleanSetting(id: String, updatedAt: Long)

    // ── Lecture d'une ligne, supprimée ou non ───────────────────────────
    @Query("SELECT * FROM devices WHERE id = :id") abstract suspend fun device(id: String): Device?
    @Query("SELECT * FROM categories WHERE id = :id") abstract suspend fun category(id: String): Category?
    @Query("SELECT * FROM products WHERE id = :id") abstract suspend fun product(id: String): Product?
    @Query("SELECT * FROM channels WHERE id = :id") abstract suspend fun channel(id: String): ChannelEntity?
    @Query("SELECT * FROM cart_lines WHERE id = :id") abstract suspend fun cartLine(id: String): CartLineEntity?
    @Query("SELECT * FROM settings WHERE id = :id") abstract suspend fun setting(id: String): SharedSetting?

    // ── Fusion de doublons décidée par le serveur (ancien id → id officiel) ──
    @Query("UPDATE channels SET deviceId = :to WHERE deviceId = :from") abstract suspend fun remapDevice(from: String, to: String)
    @Query("UPDATE products SET categoryId = :to WHERE categoryId = :from") abstract suspend fun remapCategory(from: String, to: String)
    @Query("UPDATE cart_lines SET productId = :to WHERE productId = :from") abstract suspend fun remapProduct(from: String, to: String)
    @Query("UPDATE cart_lines SET channelId = :to WHERE channelId = :from") abstract suspend fun remapChannel(from: String, to: String)

    @Query("DELETE FROM devices WHERE id = :id") abstract suspend fun hardDeleteDevice(id: String)
    @Query("DELETE FROM categories WHERE id = :id") abstract suspend fun hardDeleteCategory(id: String)
    @Query("DELETE FROM products WHERE id = :id") abstract suspend fun hardDeleteProduct(id: String)
    @Query("DELETE FROM channels WHERE id = :id") abstract suspend fun hardDeleteChannel(id: String)
    @Query("DELETE FROM cart_lines WHERE id = :id") abstract suspend fun hardDeleteCartLine(id: String)
    @Query("DELETE FROM settings WHERE id = :id") abstract suspend fun hardDeleteSetting(id: String)

    // ── Serveur réinitialisé : tout renvoyer ────────────────────────────
    @Query("UPDATE devices SET dirty = 1") abstract suspend fun allDevicesDirty()
    @Query("UPDATE categories SET dirty = 1") abstract suspend fun allCategoriesDirty()
    @Query("UPDATE products SET dirty = 1") abstract suspend fun allProductsDirty()
    @Query("UPDATE channels SET dirty = 1") abstract suspend fun allChannelsDirty()
    @Query("UPDATE cart_lines SET dirty = 1") abstract suspend fun allCartLinesDirty()
    @Query("UPDATE settings SET dirty = 1") abstract suspend fun allSettingsDirty()
}
