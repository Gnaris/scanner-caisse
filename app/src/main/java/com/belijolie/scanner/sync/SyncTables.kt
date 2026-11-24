package com.belijolie.scanner.sync

import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.entity.CartLineEntity
import com.belijolie.scanner.data.entity.Category
import com.belijolie.scanner.data.entity.ChannelEntity
import com.belijolie.scanner.data.entity.Device
import com.belijolie.scanner.data.entity.Product
import com.belijolie.scanner.data.entity.SharedSetting
import org.json.JSONObject

/**
 * Description d'une table synchronisée : comment lire les lignes à envoyer,
 * les convertir en JSON (mêmes noms de champs que le serveur, voir
 * server/src/entities.js) et appliquer une ligne reçue.
 */
internal class SyncTable<T : Any>(
    /** Nom de la table côté serveur. */
    val name: String,
    val dirty: suspend (limit: Int) -> List<T>,
    val idOf: (T) -> String,
    val updatedAtOf: (T) -> Long,
    val isDirty: (T) -> Boolean,
    val toJson: (T) -> JSONObject,
    /** Ligne reçue du serveur → entité locale (dirty = false). */
    val fromJson: (JSONObject) -> T,
    val local: suspend (id: String) -> T?,
    val upsert: suspend (T) -> Unit,
    val clean: suspend (id: String, updatedAt: Long) -> Unit,
    val allDirty: suspend () -> Unit,
)

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.strOr(key: String, default: String = ""): String = str(key) ?: default
private fun JSONObject.intOr(key: String, default: Int = 0): Int = if (isNull(key)) default else getInt(key)
private fun JSONObject.longOr(key: String, default: Long = 0): Long = if (isNull(key)) default else getLong(key)

private fun base(id: String, updatedAt: Long, deleted: Boolean) = JSONObject().apply {
    put("id", id)
    put("updatedAt", updatedAt)
    put("deleted", deleted)
}

private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

/** Ordre = ordre de dépendance (même ordre que le serveur). */
internal fun syncTables(db: AppDatabase): List<SyncTable<*>> {
    val s = db.syncDao()
    return listOf(
        SyncTable(
            name = "devices",
            dirty = { s.dirtyDevices(it) },
            idOf = { it.id }, updatedAtOf = { it.updatedAt }, isDirty = { it.dirty },
            toJson = { base(it.id, it.updatedAt, it.deleted).put("name", it.name) },
            fromJson = { o ->
                Device(id = o.getString("id"), name = o.strOr("name"), updatedAt = o.getLong("updatedAt"),
                    deleted = o.getBoolean("deleted"), dirty = false)
            },
            local = { s.device(it) },
            upsert = { db.deviceDao().upsertRaw(it) },
            clean = { id, at -> s.cleanDevice(id, at) },
            allDirty = { s.allDevicesDirty() },
        ),
        SyncTable(
            name = "categories",
            dirty = { s.dirtyCategories(it) },
            idOf = { it.id }, updatedAtOf = { it.updatedAt }, isDirty = { it.dirty },
            toJson = {
                base(it.id, it.updatedAt, it.deleted)
                    .put("name", it.name)
                    .putOpt2("nameEn", it.nameEn)
                    .putOpt2("sageArticleCode", it.sageArticleCode)
                    .putOpt2("barcodePrefix", it.barcodePrefix)
            },
            fromJson = { o ->
                Category(
                    id = o.getString("id"), name = o.strOr("name"), nameEn = o.str("nameEn"),
                    sageArticleCode = o.str("sageArticleCode"), barcodePrefix = o.str("barcodePrefix"),
                    updatedAt = o.getLong("updatedAt"), deleted = o.getBoolean("deleted"), dirty = false,
                )
            },
            local = { s.category(it) },
            upsert = { db.categoryDao().upsertRaw(it) },
            clean = { id, at -> s.cleanCategory(id, at) },
            allDirty = { s.allCategoriesDirty() },
        ),
        SyncTable(
            name = "products",
            dirty = { s.dirtyProducts(it) },
            idOf = { it.id }, updatedAtOf = { it.updatedAt }, isDirty = { it.dirty },
            toJson = {
                base(it.id, it.updatedAt, it.deleted)
                    .put("barcode", it.barcode)
                    .put("categoryId", it.categoryId)
                    .put("priceCents", it.priceCents)
                    .putOpt2("nameEn", it.nameEn)
            },
            fromJson = { o ->
                Product(
                    id = o.getString("id"), barcode = o.strOr("barcode"), categoryId = o.strOr("categoryId"),
                    priceCents = o.intOr("priceCents"), nameEn = o.str("nameEn"),
                    updatedAt = o.getLong("updatedAt"), deleted = o.getBoolean("deleted"), dirty = false,
                )
            },
            local = { s.product(it) },
            upsert = { db.productDao().upsertRaw(it) },
            clean = { id, at -> s.cleanProduct(id, at) },
            allDirty = { s.allProductsDirty() },
        ),
        SyncTable(
            name = "channels",
            dirty = { s.dirtyChannels(it) },
            idOf = { it.id }, updatedAtOf = { it.updatedAt }, isDirty = { it.dirty },
            toJson = {
                base(it.id, it.updatedAt, it.deleted)
                    .put("deviceId", it.deviceId)
                    .put("name", it.name)
                    .put("discountPercent", it.discountPercent)
                    .put("createdAt", it.createdAt)
            },
            fromJson = { o ->
                ChannelEntity(
                    id = o.getString("id"), deviceId = o.strOr("deviceId"), name = o.strOr("name"),
                    discountPercent = o.intOr("discountPercent"), createdAt = o.longOr("createdAt"),
                    updatedAt = o.getLong("updatedAt"), deleted = o.getBoolean("deleted"), dirty = false,
                )
            },
            local = { s.channel(it) },
            upsert = { db.channelDao().upsertRaw(it) },
            clean = { id, at -> s.cleanChannel(id, at) },
            allDirty = { s.allChannelsDirty() },
        ),
        SyncTable(
            name = "cart_lines",
            dirty = { s.dirtyCartLines(it) },
            idOf = { it.id }, updatedAtOf = { it.updatedAt }, isDirty = { it.dirty },
            toJson = {
                base(it.id, it.updatedAt, it.deleted)
                    .put("channelId", it.channelId)
                    .putOpt2("productId", it.productId)
                    .put("barcode", it.barcode)
                    .put("categoryName", it.categoryName)
                    .putOpt2("categoryNameEn", it.categoryNameEn)
                    .putOpt2("nameEn", it.nameEn)
                    .putOpt2("sageArticleCode", it.sageArticleCode)
                    .put("unitPriceCents", it.unitPriceCents)
                    .put("quantity", it.quantity)
                    .put("createdAt", it.createdAt)
            },
            fromJson = { o ->
                CartLineEntity(
                    id = o.getString("id"), channelId = o.strOr("channelId"), productId = o.str("productId"),
                    barcode = o.strOr("barcode"), categoryName = o.strOr("categoryName"),
                    categoryNameEn = o.str("categoryNameEn"), nameEn = o.str("nameEn"),
                    sageArticleCode = o.str("sageArticleCode"), unitPriceCents = o.intOr("unitPriceCents"),
                    quantity = o.intOr("quantity", 1), createdAt = o.longOr("createdAt"),
                    updatedAt = o.getLong("updatedAt"), deleted = o.getBoolean("deleted"), dirty = false,
                )
            },
            local = { s.cartLine(it) },
            upsert = { db.channelDao().upsertLineRaw(it) },
            clean = { id, at -> s.cleanCartLine(id, at) },
            allDirty = { s.allCartLinesDirty() },
        ),
        SyncTable(
            name = "settings",
            dirty = { s.dirtySettings(it) },
            idOf = { it.id }, updatedAtOf = { it.updatedAt }, isDirty = { it.dirty },
            toJson = { base(it.id, it.updatedAt, it.deleted).put("value", it.value) },
            fromJson = { o ->
                SharedSetting(id = o.getString("id"), value = o.strOr("value"), updatedAt = o.getLong("updatedAt"),
                    deleted = o.getBoolean("deleted"), dirty = false)
            },
            local = { s.setting(it) },
            upsert = { db.settingDao().upsertRaw(it) },
            clean = { id, at -> s.cleanSetting(id, at) },
            allDirty = { s.allSettingsDirty() },
        ),
    )
}
