package com.belijolie.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.belijolie.scanner.scanner.ScanReceiver
import com.belijolie.scanner.ui.Destination
import com.belijolie.scanner.ui.SubRoutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.belijolie.scanner.ui.categories.CategoriesScreen
import com.belijolie.scanner.ui.checkout.ChannelDetailScreen
import com.belijolie.scanner.ui.checkout.ChannelsListScreen
import com.belijolie.scanner.ui.products.ProductsScreen
import com.belijolie.scanner.ui.settings.SettingsScreen
import com.belijolie.scanner.ui.theme.ScannerTheme
import com.belijolie.scanner.update.UpdateDialog
import com.belijolie.scanner.update.UpdateState
import com.belijolie.scanner.util.isNetworkConnected
import androidx.compose.ui.platform.LocalContext

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
    val currentRoute = backStack?.destination?.route ?: Destination.Channels.route

    val updateManager = ScannerApp.instance.updateManager
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Au démarrage, splash pendant le check de MAJ. Si aucun réseau n'est
    // disponible → on skip le check et l'app démarre direct : continuité
    // assurée hors Wi-Fi. Si réseau OK → silent=false pour transition
    // d'état propre (le dialog MAJ ne s'affiche que si une version plus
    // récente existe — pas de dialog "déjà à jour"). La MAJ n'est jamais
    // obligatoire, le dialog Available a toujours un bouton "Plus tard".
    var initialCheckDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (isNetworkConnected(context)) {
            updateManager.checkForUpdate(silent = false)
        } else {
            initialCheckDone = true
        }
    }
    LaunchedEffect(updateState) {
        if (updateState !is UpdateState.Idle && updateState !is UpdateState.Checking) {
            initialCheckDone = true
        }
    }

    if (!initialCheckDone) {
        StartupSplash()
        return
    }

    if (updateState !is UpdateState.Idle &&
        updateState !is UpdateState.Checking &&
        updateState !is UpdateState.UpToDate
    ) {
        UpdateDialog(
            state = updateState,
            onDismiss = { updateManager.reset() },
        )
    }

    Scaffold(
        bottomBar = {
            // NavigationBar par défaut = 80.dp — trop haut pour un PDA. On
            // compresse à 54 dp (sans label) et on force une icône 20 dp pour
            // libérer de la place sur l'écran de caisse.
            NavigationBar(
                // Barre un peu plus haute (62.dp au lieu de 54.dp) pour laisser
                // la place aux labels sur 2 lignes (FR puis 中文).
                modifier = Modifier.height(62.dp),
                windowInsets = WindowInsets(0),
                tonalElevation = 2.dp
            ) {
                Destination.entries.forEach { dest ->
                    val label = stringResource(dest.labelRes)
                    // L'onglet « Canal » reste surligné quand on est dans le
                    // détail d'un canal (route "channel/{id}"), pour cohérence
                    // visuelle utilisateur.
                    val isChannelsTab = dest == Destination.Channels &&
                        (currentRoute == Destination.Channels.route ||
                            currentRoute?.startsWith("channel/") == true)
                    NavigationBarItem(
                        selected = currentRoute == dest.route || isChannelsTab,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navController.navigate(dest.route) {
                                    popUpTo(Destination.Channels.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                dest.icon,
                                contentDescription = label,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                label,
                                fontSize = 9.sp,
                                maxLines = 2,
                                lineHeight = 10.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors()
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Channels.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Channels.route) {
                ChannelsListScreen(
                    onOpenChannel = { id ->
                        // launchSingleTop + popUpTo(current, inclusive=false) :
                        // si plusieurs créations en rafale déclenchent plusieurs
                        // navigate(), on ne garde qu'une seule instance détail
                        // en haut du backstack et on ne la rempile pas.
                        navController.navigate(SubRoutes.channelDetail(id)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(
                route = SubRoutes.CHANNEL_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                ChannelDetailScreen(
                    channelId = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Destination.Products.route) { ProductsScreen() }
            composable(Destination.Categories.route) { CategoriesScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}

/**
 * Splash plein écran affiché au tout démarrage pendant que l'app vérifie si
 * une mise à jour est disponible. Bloque toute interaction avec la caisse
 * tant qu'on ne sait pas si une version plus récente est à installer, pour
 * éviter que l'utilisateur fasse une transaction sur une version obsolète.
 */
@androidx.compose.runtime.Composable
private fun StartupSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF4F46E5)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.update_checking),
                color = Color.White,
                fontSize = 14.sp,
            )
        }
    }
}
