package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.belijolie.scanner.data.newId
import com.belijolie.scanner.data.nowMs

/** Un canal (panier en cours) rattaché à une caisse. */
@Entity(tableName = "channels", indices = [Index("deviceId")])
data class ChannelEntity(
    @PrimaryKey val id: String = newId(),
    val deviceId: String,
    val name: String,
    // Remise de caisse 0-100 (affichage + ticket uniquement, jamais l'export).
    val discountPercent: Int = 0,
    val createdAt: Long = nowMs(),
    val updatedAt: Long = nowMs(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)
