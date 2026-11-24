package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.belijolie.scanner.data.newId
import com.belijolie.scanner.data.nowMs

/**
 * Une caisse (« Appareil 1 », « Appareil 2 »…). Choisie dans la liste
 * déroulante à l'ouverture de l'app ; chaque caisse a ses propres canaux.
 * N'importe quel PDA physique peut ouvrir n'importe quelle caisse.
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val updatedAt: Long = nowMs(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)
