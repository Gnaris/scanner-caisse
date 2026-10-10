package com.belijolie.scanner.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Checkout("checkout", "Caisse", Icons.Filled.PointOfSale),
    Products("products", "Produits", Icons.Filled.ShoppingBag),
    Categories("categories", "Catégories", Icons.Filled.Category),
    Settings("settings", "Paramètres", Icons.Filled.Settings)
}
