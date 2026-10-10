package com.belijolie.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.belijolie.scanner.scanner.ScanReceiver
import com.belijolie.scanner.ui.Destination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.belijolie.scanner.ui.categories.CategoriesScreen
import com.belijolie.scanner.ui.checkout.CheckoutScreen
import com.belijolie.scanner.ui.products.ProductsScreen
import com.belijolie.scanner.ui.settings.SettingsScreen
import com.belijolie.scanner.ui.theme.ScannerTheme

class MainActivity : ComponentActivity() {

    private var receiver: ScanReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScannerTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as ScannerApp
        val settings = runBlocking { app.settingsStore.settings.first() }
        receiver = ScanReceiver.register(this, settings.scannerAction, settings.scannerExtra)
    }

    override fun onStop() {
        super.onStop()
        receiver?.let { ScanReceiver.unregister(this, it) }
        receiver = null
    }
}

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Destination.Checkout.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navController.navigate(dest.route) {
                                    popUpTo(Destination.Checkout.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Checkout.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Checkout.route) { CheckoutScreen() }
            composable(Destination.Products.route) { ProductsScreen() }
            composable(Destination.Categories.route) { CategoriesScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
