package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.belijolie.scanner.data.entity.Device
import com.belijolie.scanner.data.nowMs
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DeviceDao {

    @Query("SELECT * FROM devices WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM devices WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    abstract suspend fun getAll(): List<Device>

    @Query("SELECT * FROM devices WHERE id = :id AND deleted = 0")
    abstract suspend fun getById(id: String): Device?

    @Query("SELECT * FROM devices WHERE name = :name COLLATE NOCASE AND deleted = 0 LIMIT 1")
    abstract suspend fun findByName(name: String): Device?

    @Upsert
    abstract suspend fun upsertRaw(device: Device)

    open suspend fun save(device: Device) {
        upsertRaw(device.copy(updatedAt = nowMs(), dirty = true))
    }

    @Query("UPDATE devices SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long = nowMs())
}
