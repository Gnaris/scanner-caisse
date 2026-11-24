package com.belijolie.scanner.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.belijolie.scanner.data.entity.SharedSetting
import com.belijolie.scanner.data.nowMs
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SettingDao {

    @Query("SELECT * FROM settings WHERE deleted = 0")
    abstract fun observeAll(): Flow<List<SharedSetting>>

    @Query("SELECT * FROM settings WHERE deleted = 0")
    abstract suspend fun getAll(): List<SharedSetting>

    @Upsert
    abstract suspend fun upsertRaw(setting: SharedSetting)

    open suspend fun save(key: String, value: String) {
        upsertRaw(SharedSetting(id = key, value = value, updatedAt = nowMs(), dirty = true))
    }
}
