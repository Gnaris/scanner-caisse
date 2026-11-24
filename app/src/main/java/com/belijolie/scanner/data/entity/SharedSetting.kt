package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.belijolie.scanner.data.nowMs

/**
 * Paramètre boutique commun à tous les appareils (nom, adresse, QR, code
 * Sage manuel…). [id] = clé du paramètre (voir SettingsStore.SharedKeys).
 * Les paramètres propres au PDA (imprimante, scanner, URL export PC) restent
 * dans DataStore et ne sont jamais synchronisés.
 */
@Entity(tableName = "settings")
data class SharedSetting(
    @PrimaryKey val id: String,
    val value: String,
    val updatedAt: Long = nowMs(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)
