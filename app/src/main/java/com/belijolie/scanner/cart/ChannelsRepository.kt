package com.belijolie.scanner.cart

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.channelsDataStore by preferencesDataStore(name = "channels")

/**
 * Persists the in-progress channels (carts) in a lightweight DataStore,
 * so the user does not lose a cart if the app is killed or the device reboots.
 *
 * This is NOT a ticket history: as soon as a ticket is printed, the channel
 * is removed from storage.
 */
class ChannelsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val stateKey = stringPreferencesKey("state_json")

    val state: Flow<ChannelsState> = context.channelsDataStore.data.map { prefs ->
        val raw = prefs[stateKey] ?: return@map ChannelsState()
        runCatching { json.decodeFromString<ChannelsState>(raw) }.getOrDefault(ChannelsState())
    }

    suspend fun current(): ChannelsState = state.first()

    /**
     * Mutation atomique de l'état. Lecture + modif + écriture se font dans
     * le même `edit {}` block de DataStore, qui sérialise les transactions
     * concurrentes. Sans ça, deux coroutines parallèles (ex : closeChannel +
     * clearActive déclenchés par la navigation back) peuvent chacune lire
     * l'état avant que l'autre n'ait sauvé, et le 2e save écrase le 1er —
     * c'est ce qui faisait "revenir" un canal clôturé quand c'était le seul
     * de la liste.
     */
    private suspend fun mutate(transform: (ChannelsState) -> ChannelsState) {
        context.channelsDataStore.edit { prefs ->
            val raw = prefs[stateKey]
            val currentState = raw
                ?.let { runCatching { json.decodeFromString<ChannelsState>(it) }.getOrNull() }
                ?: ChannelsState()
            prefs[stateKey] = json.encodeToString(transform(currentState))
        }
    }

    suspend fun createChannel(name: String): Channel {
        val channel = Channel(name = name)
        mutate { current ->
            current.copy(
                channels = current.channels + channel,
                activeChannelId = channel.id
            )
        }
        return channel
    }

    suspend fun setActive(channelId: String?) {
        mutate { it.copy(activeChannelId = channelId) }
    }

    suspend fun clearActive() {
        mutate { it.copy(activeChannelId = null) }
    }

    suspend fun setDiscountInActive(percent: Int) {
        mutate { st ->
            val activeId = st.activeChannelId ?: return@mutate st
            st.copy(channels = st.channels.map { ch ->
                if (ch.id == activeId) ch.setDiscount(percent) else ch
            })
        }
    }

    suspend fun rename(channelId: String, newName: String) {
        mutate { st ->
            st.copy(channels = st.channels.map { if (it.id == channelId) it.rename(newName) else it })
        }
    }

    suspend fun closeChannel(channelId: String) {
        mutate { st ->
            val remaining = st.channels.filterNot { it.id == channelId }
            val nextActive =
                if (st.activeChannelId == channelId) remaining.firstOrNull()?.id else st.activeChannelId
            st.copy(channels = remaining, activeChannelId = nextActive)
        }
    }

    suspend fun addProductToActive(
        productId: Long,
        barcode: String,
        categoryName: String,
        priceCents: Int,
        categoryNameEn: String? = null,
        nameEn: String? = null,
        sageArticleCode: String? = null,
        quantity: Int = 1
    ) {
        mutate { st ->
            val activeId = st.activeChannelId ?: return@mutate st
            st.copy(channels = st.channels.map { ch ->
                if (ch.id == activeId)
                    ch.addProduct(productId, barcode, categoryName, priceCents, categoryNameEn, nameEn, sageArticleCode, quantity)
                else ch
            })
        }
    }

    suspend fun removeLineFromActive(productId: Long) {
        mutate { st ->
            val activeId = st.activeChannelId ?: return@mutate st
            st.copy(channels = st.channels.map { ch ->
                if (ch.id == activeId) ch.removeLine(productId) else ch
            })
        }
    }

    suspend fun setLineQuantityInActive(productId: Long, quantity: Int) {
        mutate { st ->
            val activeId = st.activeChannelId ?: return@mutate st
            st.copy(channels = st.channels.map { ch ->
                if (ch.id == activeId) ch.setLineQuantity(productId, quantity) else ch
            })
        }
    }

    suspend fun editLineInActive(
        productId: Long,
        newCategoryName: String,
        newPriceCents: Int,
        newQuantity: Int,
        newCategoryNameEn: String? = null,
        newSageArticleCode: String? = null,
    ) {
        mutate { st ->
            val activeId = st.activeChannelId ?: return@mutate st
            st.copy(channels = st.channels.map { ch ->
                if (ch.id == activeId) ch.editLine(
                    productId = productId,
                    newCategoryName = newCategoryName,
                    newPriceCents = newPriceCents,
                    newQuantity = newQuantity,
                    newCategoryNameEn = newCategoryNameEn,
                    newSageArticleCode = newSageArticleCode,
                ) else ch
            })
        }
    }

    /** Called after a successful print: bumps the ticket counter, keeps the channel open. */
    suspend fun bumpTicketNumber(): Int {
        var ticketNumber = 0
        mutate { st ->
            ticketNumber = st.nextTicketNumber
            st.copy(nextTicketNumber = ticketNumber + 1)
        }
        return ticketNumber
    }
}
