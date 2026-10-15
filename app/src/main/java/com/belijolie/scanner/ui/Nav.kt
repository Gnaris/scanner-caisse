package com.belijolie.scanner.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector
import com.belijolie.scanner.R

enum class Destination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    Channels("channels", R.string.nav_channels, Icons.AutoMirrored.Filled.ReceiptLong),
    Products("products", R.string.nav_products, Icons.Filled.ShoppingBag),
    Categories("categories", R.string.nav_categories, Icons.Filled.Category),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings)
}

/**
 * Routes non visibles dans la bottom bar (sous-écrans d'une destination).
 * Le détail d'un canal ne doit pas apparaître dans la bottom nav.
 */
object SubRoutes {
    const val CHANNEL_DETAIL = "channel/{id}"
    fun channelDetail(id: String) = "channel/$id"
}
