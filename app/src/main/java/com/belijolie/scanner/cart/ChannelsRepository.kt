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

    private suspend fun save(state: ChannelsState) {
        context.channelsDataStore.edit { prefs ->
            prefs[stateKey] = json.encodeToString(state)
        }
    }

    suspend fun createChannel(name: String): Channel {
        val current = current()
        val channel = Channel(name = name)
        save(current.copy(
            channels = current.channels + channel,
            activeChannelId = channel.id
        ))
        return channel
    }

    suspend fun setActive(channelId: String) {
        save(current().copy(activeChannelId = channelId))
    }

    suspend fun rename(channelId: String, newName: String) {
        val st = current()
        save(st.copy(channels = st.channels.map { if (it.id == channelId) it.rename(newName) else it }))
    }

    suspend fun closeChannel(channelId: String) {
        val st = current()
        val remaining = st.channels.filterNot { it.id == channelId }
        val nextActive = if (st.activeChannelId == channelId) remaining.firstOrNull()?.id else st.activeChannelId
        save(st.copy(channels = remaining, activeChannelId = nextActive))
    }

    suspend fun addProductToActive(
        productId: Long,
        barcode: String,
        categoryName: String,
        priceCents: Int
    ) {
        val st = current()
        val activeId = st.activeChannelId ?: return
        val updated = st.channels.map { ch ->
            if (ch.id == activeId) ch.addProduct(productId, barcode, categoryName, priceCents) else ch
        }
        save(st.copy(channels = updated))
    }

    suspend fun removeLineFromActive(productId: Long) {
        val st = current()
        val activeId = st.activeChannelId ?: return
        val updated = st.channels.map { ch ->
            if (ch.id == activeId) ch.removeLine(productId) else ch
        }
        save(st.copy(channels = updated))
    }

    /** Called after a successful print: bumps the ticket counter, keeps the channel open. */
    suspend fun bumpTicketNumber(): Int {
        val st = current()
        val ticketNumber = st.nextTicketNumber
        save(st.copy(nextTicketNumber = ticketNumber + 1))
        return ticketNumber
    }
}
