package com.belijolie.scanner.cart

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.DeviceSession
import com.belijolie.scanner.data.entity.CartLineEntity
import com.belijolie.scanner.data.entity.ChannelEntity
import com.belijolie.scanner.data.nowMs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Ancien stockage des canaux (JSON dans DataStore, avant la v7). Lu une
// seule fois par [importLegacyChannels] puis vidé.
private val Context.legacyChannelsStore by preferencesDataStore(name = "channels")

/**
 * Canaux (paniers en cours) de la caisse ouverte, stockés dans Room et
 * synchronisés avec le serveur.
 *
 * Chaque ajout au panier est une ligne [CartLineEntity] distincte ; l'écran
 * voit des [CartLine] regroupées par (catégorie, prix). Toutes les
 * opérations sur une ligne affichée passent par sa [CartLine.key].
 *
 * Ce n'est PAS un historique de tickets : imprimer ne ferme pas le canal,
 * la fermeture est manuelle.
 */
class ChannelsRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val session: DeviceSession,
) {
    private val dao = db.channelDao()

    /** Canal dont le détail est affiché (les scans y sont ajoutés). Non persisté. */
    private val activeChannelId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<ChannelsState> = session.currentDeviceId.flatMapLatest { deviceId ->
        if (deviceId == null) flowOf(ChannelsState())
        else combine(
            dao.observeForDevice(deviceId),
            dao.observeLinesForDevice(deviceId),
            activeChannelId,
        ) { channels, rows, active ->
            val rowsByChannel = rows.groupBy { it.channelId }
            ChannelsState(
                channels = channels.map { ch ->
                    Channel(
                        id = ch.id,
                        name = ch.name,
                        lines = groupLines(rowsByChannel[ch.id].orEmpty()),
                        discountPercent = ch.discountPercent,
                    )
                },
                activeChannelId = active,
            )
        }
    }

    suspend fun current(): ChannelsState = state.first()

    suspend fun createChannel(name: String): Channel {
        val deviceId = session.currentDeviceId.first()
            ?: error("Aucune caisse ouverte")
        val entity = ChannelEntity(deviceId = deviceId, name = name)
        dao.save(entity)
        activeChannelId.value = entity.id
        return Channel(id = entity.id, name = entity.name)
    }

    fun setActive(channelId: String?) {
        activeChannelId.value = channelId
    }

    fun clearActive() {
        activeChannelId.value = null
    }

    suspend fun setDiscountInActive(percent: Int) {
        val ch = activeChannelId.value?.let { dao.getById(it) } ?: return
        dao.save(ch.copy(discountPercent = percent.coerceIn(0, 100)))
    }

    suspend fun rename(channelId: String, newName: String) {
        val ch = dao.getById(channelId) ?: return
        dao.save(ch.copy(name = newName))
    }

    suspend fun closeChannel(channelId: String) = closeChannels(setOf(channelId))

    /** Supprime plusieurs canaux (et leurs paniers) en une seule transaction. */
    suspend fun closeChannels(channelIds: Collection<String>) {
        if (channelIds.isEmpty()) return
        db.withTransaction {
            for (id in channelIds) {
                dao.softDeleteLinesOfChannel(id)
                dao.softDelete(id)
            }
        }
        if (activeChannelId.value in channelIds) activeChannelId.value = null
    }

    suspend fun addProductToActive(
        productId: String?,
        barcode: String,
        categoryName: String,
        priceCents: Int,
        categoryNameEn: String? = null,
        nameEn: String? = null,
        sageArticleCode: String? = null,
        quantity: Int = 1
    ) {
        val channelId = activeChannelId.value ?: return
        dao.saveLine(
            CartLineEntity(
                channelId = channelId,
                productId = productId,
                barcode = barcode,
                categoryName = categoryName,
                categoryNameEn = categoryNameEn,
                nameEn = nameEn,
                sageArticleCode = sageArticleCode,
                unitPriceCents = priceCents,
                quantity = quantity.coerceAtLeast(1),
            )
        )
    }

    /** Ajouts du canal actif qui composent la ligne affichée [key]. */
    private suspend fun groupRows(key: String): Pair<String, List<CartLineEntity>>? {
        val channelId = activeChannelId.value ?: return null
        val rows = dao.getLines(channelId).filter { lineKey(it.categoryName, it.unitPriceCents) == key }
        return if (rows.isEmpty()) null else channelId to rows
    }

    suspend fun removeLineFromActive(key: String) {
        val (_, rows) = groupRows(key) ?: return
        dao.softDeleteLines(rows.map { it.id })
    }

    /**
     * Positionne la quantité absolue d'une ligne affichée. Si [quantity] ≤ 0
     * la ligne est retirée. Les ajouts regroupés sont remplacés par un seul.
     * Un ajout fait entre-temps sur un autre PDA (pas encore reçu) reste en
     * plus, il n'est jamais perdu.
     */
    suspend fun setLineQuantityInActive(key: String, quantity: Int) {
        if (quantity <= 0) return removeLineFromActive(key)
        val (channelId, rows) = groupRows(key) ?: return
        val line = groupLines(rows).first()
        replaceGroup(rows, channelId, line, line.categoryName, line.unitPriceCents, quantity, line.categoryNameEn, line.sageArticleCode)
    }

    /**
     * Édite une ligne affichée (catégorie, prix, quantité). Si la nouvelle
     * (catégorie, prix) correspond à une autre ligne, elles fusionnent
     * d'elles-mêmes à l'affichage.
     */
    suspend fun editLineInActive(
        key: String,
        newCategoryName: String,
        newPriceCents: Int,
        newQuantity: Int,
        newCategoryNameEn: String? = null,
        newSageArticleCode: String? = null,
    ) {
        val (channelId, rows) = groupRows(key) ?: return
        val line = groupLines(rows).first()
        replaceGroup(
            rows, channelId, line, newCategoryName, newPriceCents, newQuantity.coerceAtLeast(1),
            newCategoryNameEn ?: line.categoryNameEn,
            newSageArticleCode ?: line.sageArticleCode,
        )
    }

    private suspend fun replaceGroup(
        rows: List<CartLineEntity>,
        channelId: String,
        line: CartLine,
        categoryName: String,
        priceCents: Int,
        quantity: Int,
        categoryNameEn: String?,
        sageArticleCode: String?,
    ) {
        db.withTransaction {
            dao.softDeleteLines(rows.map { it.id })
            dao.saveLine(
                CartLineEntity(
                    channelId = channelId,
                    productId = line.productId,
                    barcode = line.barcode,
                    categoryName = categoryName,
                    categoryNameEn = categoryNameEn,
                    nameEn = line.nameEn,
                    sageArticleCode = sageArticleCode,
                    unitPriceCents = priceCents,
                    quantity = quantity,
                    // Garde la position de la ligne dans le panier
                    createdAt = rows.minOf { it.createdAt },
                )
            )
        }
    }

    // ── Reprise des canaux de l'ancienne version (DataStore JSON) ──────────

    @Serializable
    private data class LegacyLine(
        val barcode: String = "",
        val categoryName: String,
        val unitPriceCents: Int,
        val quantity: Int,
        val categoryNameEn: String? = null,
        val nameEn: String? = null,
        val sageArticleCode: String? = null,
    )

    @Serializable
    private data class LegacyChannel(
        val id: String,
        val name: String,
        val lines: List<LegacyLine> = emptyList(),
        val discountPercent: Int = 0,
    )

    @Serializable
    private data class LegacyState(val channels: List<LegacyChannel> = emptyList())

    /**
     * Une seule fois après la mise à jour v7, dès qu'une caisse est ouverte :
     * recopie les canaux en cours de l'ancienne version dans cette caisse.
     */
    suspend fun importLegacyChannels(deviceId: String) {
        val key = stringPreferencesKey("state_json")
        val raw = context.legacyChannelsStore.data.first()[key] ?: return
        val legacy = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<LegacyState>(raw) }
            .getOrNull()
        if (legacy != null) {
            val base = nowMs()
            db.withTransaction {
                legacy.channels.forEachIndexed { i, ch ->
                    if (dao.getById(ch.id) != null) return@forEachIndexed
                    val createdAt = base + i
                    dao.save(
                        ChannelEntity(
                            id = ch.id,
                            deviceId = deviceId,
                            name = ch.name,
                            discountPercent = ch.discountPercent.coerceIn(0, 100),
                            createdAt = createdAt,
                        )
                    )
                    ch.lines.forEachIndexed { j, l ->
                        dao.saveLine(
                            CartLineEntity(
                                channelId = ch.id,
                                productId = null,
                                barcode = l.barcode,
                                categoryName = l.categoryName,
                                categoryNameEn = l.categoryNameEn,
                                nameEn = l.nameEn,
                                sageArticleCode = l.sageArticleCode,
                                unitPriceCents = l.unitPriceCents,
                                quantity = l.quantity.coerceAtLeast(1),
                                createdAt = createdAt + j,
                            )
                        )
                    }
                }
            }
        }
        context.legacyChannelsStore.edit { it.remove(key) }
    }
}
