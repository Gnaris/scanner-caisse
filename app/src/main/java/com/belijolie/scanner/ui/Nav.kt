package com.belijolie.scanner.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector
import com.belijolie.scanner.R

enum class Destination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    Checkout("checkout", R.string.nav_checkout, Icons.Filled.PointOfSale),
    Products("products", R.string.nav_products, Icons.Filled.ShoppingBag),
    Categories("categories", R.string.nav_categories, Icons.Filled.Category),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings)
}
