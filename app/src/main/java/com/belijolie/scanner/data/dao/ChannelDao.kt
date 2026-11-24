package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.belijolie.scanner.data.entity.CartLineEntity
import com.belijolie.scanner.data.entity.ChannelEntity
import com.belijolie.scanner.data.nowMs
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChannelDao {

    @Query("SELECT * FROM channels WHERE deviceId = :deviceId AND deleted = 0 ORDER BY createdAt")
    abstract fun observeForDevice(deviceId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE deviceId = :deviceId AND deleted = 0 ORDER BY createdAt")
    abstract suspend fun getForDevice(deviceId: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id AND deleted = 0")
    abstract suspend fun getById(id: String): ChannelEntity?

    @Upsert
    abstract suspend fun upsertRaw(channel: ChannelEntity)

    open suspend fun save(channel: ChannelEntity) {
        upsertRaw(channel.copy(updatedAt = nowMs(), dirty = true))
    }

    @Query("UPDATE channels SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long = nowMs())

    @Query("UPDATE channels SET deleted = 1, dirty = 1, updatedAt = :now WHERE deviceId = :deviceId AND deleted = 0")
    abstract suspend fun softDeleteForDevice(deviceId: String, now: Long = nowMs())

    // ── Lignes de panier ────────────────────────────────────────────────

    /** Tous les ajouts des canaux ouverts d'une caisse. */
    @Query("""
        SELECT cart_lines.* FROM cart_lines
        INNER JOIN channels ON channels.id = cart_lines.channelId
        WHERE channels.deviceId = :deviceId AND channels.deleted = 0 AND cart_lines.deleted = 0
        ORDER BY cart_lines.createdAt
    """)
    abstract fun observeLinesForDevice(deviceId: String): Flow<List<CartLineEntity>>

    @Query("SELECT * FROM cart_lines WHERE channelId = :channelId AND deleted = 0 ORDER BY createdAt")
    abstract suspend fun getLines(channelId: String): List<CartLineEntity>

    @Upsert
    abstract suspend fun upsertLineRaw(line: CartLineEntity)

    open suspend fun saveLine(line: CartLineEntity) {
        upsertLineRaw(line.copy(updatedAt = nowMs(), dirty = true))
    }

    @Query("UPDATE cart_lines SET deleted = 1, dirty = 1, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun softDeleteLines(ids: List<String>, now: Long = nowMs())

    @Query("UPDATE cart_lines SET deleted = 1, dirty = 1, updatedAt = :now WHERE channelId = :channelId AND deleted = 0")
    abstract suspend fun softDeleteLinesOfChannel(channelId: String, now: Long = nowMs())

    @Query("""
        UPDATE cart_lines SET deleted = 1, dirty = 1, updatedAt = :now
        WHERE deleted = 0 AND channelId IN (SELECT id FROM channels WHERE deviceId = :deviceId)
    """)
    abstract suspend fun softDeleteLinesOfDevice(deviceId: String, now: Long = nowMs())
}
