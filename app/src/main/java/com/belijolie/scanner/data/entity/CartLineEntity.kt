package com.belijolie.scanner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.belijolie.scanner.data.newId
import com.belijolie.scanner.data.nowMs

/**
 * Un AJOUT au panier (un scan, un ajout manuel, une correction…), pas une
 * ligne affichée. L'écran regroupe les ajouts par (catégorie, prix unitaire)
 * en sommant les quantités (voir ChannelsRepository).
 *
 * Pourquoi : deux PDA ouverts sur le même canal, même hors-ligne, ajoutent
 * chacun leurs propres lignes → à la synchro elles s'additionnent au lieu
 * de s'écraser (aucun scan perdu).
 */
@Entity(tableName = "cart_lines", indices = [Index("channelId")])
data class CartLineEntity(
    @PrimaryKey val id: String = newId(),
    val channelId: String,
    // Produit du catalogue d'origine (null pour un ajout manuel).
    val productId: String? = null,
    // "" pour un ajout manuel (l'export Sage s'en sert pour choisir le code
    // article manuel).
    val barcode: String,
    val categoryName: String,
    // Snapshots pris au moment de l'ajout, pour le ticket EN et l'export Sage.
    val categoryNameEn: String? = null,
    val nameEn: String? = null,
    val sageArticleCode: String? = null,
    val unitPriceCents: Int,
    val quantity: Int,
    val createdAt: Long = nowMs(),
    val updatedAt: Long = nowMs(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)
