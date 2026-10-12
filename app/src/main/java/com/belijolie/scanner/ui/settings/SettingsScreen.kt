package com.belijolie.scanner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory())
) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var shopName by remember(saved.shopName) { mutableStateOf(saved.shopName) }
    var address by remember(saved.address) { mutableStateOf(saved.address) }
    var addressComplement by remember(saved.addressComplement) { mutableStateOf(saved.addressComplement) }
    var postalCode by remember(saved.postalCode) { mutableStateOf(saved.postalCode) }
    var city by remember(saved.city) { mutableStateOf(saved.city) }
    var country by remember(saved.country) { mutableStateOf(saved.country) }
    var microstoreQr by remember(saved.microstoreQr) { mutableStateOf(saved.microstoreQr) }
    var microstoreCode by remember(saved.microstoreCode) { mutableStateOf(saved.microstoreCode) }
    var websiteUrl by remember(saved.websiteUrl) { mutableStateOf(saved.websiteUrl) }
    var googleReviewUrl by remember(saved.googleReviewUrl) { mutableStateOf(saved.googleReviewUrl) }
    var ip by remember(saved.printerIp) { mutableStateOf(saved.printerIp) }
    var portText by remember(saved.printerPort) { mutableStateOf(saved.printerPort.toString()) }
    var action by remember(saved.scannerAction) { mutableStateOf(saved.scannerAction) }
    var extra by remember(saved.scannerExtra) { mutableStateOf(saved.scannerExtra) }
    var exportUrl by remember(saved.exportUrl) { mutableStateOf(saved.exportUrl) }
    var sageManualArt by remember(saved.sageManualArticleCode) { mutableStateOf(saved.sageManualArticleCode) }

    var showPreview by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Commerce", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = shopName,
            onValueChange = { shopName = it },
            label = "Nom du commerce",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = address,
            onValueChange = { address = it },
            label = "Adresse",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = addressComplement,
            onValueChange = { addressComplement = it },
            label = "Adresse complémentaire (optionnel)",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(
                value = postalCode,
                onValueChange = { postalCode = it },
                label = "Code postal",
                mode = KeyboardMode.NUMBER,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = city,
                onValueChange = { city = it },
                label = "Ville",
                mode = KeyboardMode.TEXT,
                modifier = Modifier.weight(2f)
            )
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = country,
            onValueChange = { country = it },
            label = "Pays",
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        Text("Microstore & site web", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Le QR code Microstore + le code d'accès + l'URL du site s'affichent en bas de chaque ticket.",
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = microstoreQr,
            onValueChange = { microstoreQr = it },
            label = "Contenu du QR Microstore (URL ou texte)",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = microstoreCode,
            onValueChange = { microstoreCode = it },
            label = "Code d'accès Microstore",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = websiteUrl,
            onValueChange = { websiteUrl = it },
            label = "URL du site internet",
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        Text("Avis Google", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "L'URL de la page d'avis Google de la boutique. Un QR imprimé en bas de ticket ouvrira directement la page au scan. Astuce : récupère le lien court via Google My Business (format g.page/r/…/review).",
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = googleReviewUrl,
            onValueChange = { googleReviewUrl = it },
            label = "URL d'avis Google",
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        Text("Imprimante Wi-Fi", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = ip,
            onValueChange = { ip = it },
            label = "Adresse IP",
            mode = KeyboardMode.IP
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
            label = "Port",
            mode = KeyboardMode.NUMBER
        )

        Spacer(Modifier.height(24.dp))
        Text("Scanner", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Si le scan ne fonctionne pas, ajuste l'action broadcast et le nom de l'extra selon le firmware du PDA.",
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = action,
            onValueChange = { action = it },
            label = "Action broadcast",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = extra,
            onValueChange = { extra = it },
            label = "Nom de l'extra (donnée scannée)",
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        Text("Export vers PC (Wi-Fi)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Chaque canal a un bouton d'export qui envoie un CSV (ouvert par Excel) à cette adresse. Lance le petit serveur Python décrit dans ChannelExporter.kt sur ton PC avant d'exporter.",
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = exportUrl,
            onValueChange = { exportUrl = it },
            label = "URL export PC (ex. http://192.168.1.5:8080/export)",
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = sageManualArt,
            onValueChange = { sageManualArt = it },
            label = "Code article Sage pour produits manuels (ex. ART_MANUEL)",
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { showPreview = true },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Aperçu du ticket") }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    vm.save(
                        AppSettings(
                            shopName = shopName.trim().ifBlank { AppSettings.DEFAULT.shopName },
                            address = address.trim(),
                            addressComplement = addressComplement.trim(),
                            postalCode = postalCode.trim(),
                            city = city.trim(),
                            country = country.trim(),
                            microstoreQr = microstoreQr.trim(),
                            microstoreCode = microstoreCode.trim(),
                            websiteUrl = websiteUrl.trim(),
                            googleReviewUrl = googleReviewUrl.trim(),
                            printerIp = ip.trim().ifBlank { AppSettings.DEFAULT.printerIp },
                            printerPort = portText.toIntOrNull() ?: AppSettings.DEFAULT.printerPort,
                            scannerAction = action.trim().ifBlank { AppSettings.DEFAULT.scannerAction },
                            scannerExtra = extra.trim().ifBlank { AppSettings.DEFAULT.scannerExtra },
                            exportUrl = exportUrl.trim(),
                            sageManualArticleCode = sageManualArt.trim()
                        )
                    )
                },
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Enregistrer") }

            OutlinedButton(
                onClick = { vm.testPrint() },
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Test imprimante") }
        }

        Spacer(Modifier.height(16.dp))
        SnackbarHost(hostState = snackbar) { Snackbar(snackbarData = it) }
    }

    if (showPreview) {
        TicketPreviewSheet(
            settings = AppSettings(
                shopName = shopName.trim().ifBlank { AppSettings.DEFAULT.shopName },
                address = address.trim(),
                addressComplement = addressComplement.trim(),
                postalCode = postalCode.trim(),
                city = city.trim(),
                country = country.trim(),
                microstoreQr = microstoreQr.trim(),
                microstoreCode = microstoreCode.trim(),
                websiteUrl = websiteUrl.trim(),
                googleReviewUrl = googleReviewUrl.trim(),
                printerIp = ip.trim(),
                printerPort = portText.toIntOrNull() ?: AppSettings.DEFAULT.printerPort,
                scannerAction = action.trim(),
                scannerExtra = extra.trim(),
                exportUrl = exportUrl.trim(),
                sageManualArticleCode = sageManualArt.trim()
            ),
            onDismiss = { showPreview = false }
        )
    }
}
