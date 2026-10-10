package com.belijolie.scanner.cart

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CartLine(
    val productId: Long,
    val barcode: String,
    val categoryName: String,
    val unitPriceCents: Int,
    val quantity: Int
)

@Serializable
data class Channel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lines: List<CartLine> = emptyList()
) {
    val totalCents: Int get() = lines.sumOf { it.unitPriceCents * it.quantity }

    /**
     * Merge lines when the (category, unit price) pair is the same — regardless
     * of productId or barcode. This keeps the cart tidy if the same item is
     * deleted and re-created, or if two distinct products share identical
     * category and price.
     */
    fun addProduct(productId: Long, barcode: String, categoryName: String, priceCents: Int): Channel {
        val existing = lines.indexOfFirst {
            it.categoryName == categoryName && it.unitPriceCents == priceCents
        }
        val newLines = if (existing >= 0) {
            lines.toMutableList().also {
                it[existing] = it[existing].copy(quantity = it[existing].quantity + 1)
            }
        } else {
            lines + CartLine(productId, barcode, categoryName, priceCents, 1)
        }
        return copy(lines = newLines)
    }

    fun removeLine(productId: Long): Channel =
        copy(lines = lines.filterNot { it.productId == productId })

    fun rename(newName: String): Channel = copy(name = newName)
}

@Serializable
data class ChannelsState(
    val channels: List<Channel> = emptyList(),
    val activeChannelId: String? = null,
    val nextTicketNumber: Int = 1
)
