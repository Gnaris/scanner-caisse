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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.R
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import com.belijolie.scanner.update.UpdateState

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
    // Scanner config masquée côté UI (reste persistée côté DataStore pour que
    // le PDA continue d'écouter la bonne action broadcast — valeurs par défaut
    // suffisantes pour tous les clients).
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
        SectionHeader(stringResource(R.string.settings_section_shop))
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = shopName,
            onValueChange = { shopName = it },
            label = stringResource(R.string.settings_shop_name),
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = address,
            onValueChange = { address = it },
            label = stringResource(R.string.settings_address),
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = addressComplement,
            onValueChange = { addressComplement = it },
            label = stringResource(R.string.settings_address_complement),
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(
                value = postalCode,
                onValueChange = { postalCode = it },
                label = stringResource(R.string.settings_postal_code),
                mode = KeyboardMode.NUMBER,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = city,
                onValueChange = { city = it },
                label = stringResource(R.string.settings_city),
                mode = KeyboardMode.TEXT,
                modifier = Modifier.weight(2f)
            )
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = country,
            onValueChange = { country = it },
            label = stringResource(R.string.settings_country),
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.settings_section_qr))
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = microstoreQr,
            onValueChange = { microstoreQr = it },
            label = stringResource(R.string.settings_microstore_qr),
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = microstoreCode,
            onValueChange = { microstoreCode = it },
            label = stringResource(R.string.settings_microstore_code),
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = websiteUrl,
            onValueChange = { websiteUrl = it },
            label = stringResource(R.string.settings_website_url),
            mode = KeyboardMode.TEXT
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = googleReviewUrl,
            onValueChange = { googleReviewUrl = it },
            label = stringResource(R.string.settings_google_review_url),
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.settings_section_printer))
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = ip,
            onValueChange = { ip = it },
            label = stringResource(R.string.settings_printer_ip),
            mode = KeyboardMode.IP
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
            label = stringResource(R.string.settings_printer_port),
            mode = KeyboardMode.NUMBER
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.settings_section_export))
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = exportUrl,
            onValueChange = { exportUrl = it },
            label = stringResource(R.string.settings_export_url),
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.settings_section_sage))
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = sageManualArt,
            onValueChange = { sageManualArt = it },
            label = stringResource(R.string.settings_sage_manual_code),
            mode = KeyboardMode.TEXT
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.update_section_title))
        Spacer(Modifier.height(8.dp))
        UpdateSection()

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { showPreview = true },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text(stringResource(R.string.settings_preview_ticket)) }

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
                            // Scanner config préservée telle qu'enregistrée
                            // (plus affichée dans l'UI).
                            scannerAction = saved.scannerAction,
                            scannerExtra = saved.scannerExtra,
                            exportUrl = exportUrl.trim(),
                            sageManualArticleCode = sageManualArt.trim()
                        )
                    )
                },
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(stringResource(R.string.common_save)) }

            OutlinedButton(
                onClick = { vm.testPrint() },
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(stringResource(R.string.settings_test_print)) }
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
                scannerAction = saved.scannerAction,
                scannerExtra = saved.scannerExtra,
                exportUrl = exportUrl.trim(),
                sageManualArticleCode = sageManualArt.trim()
            ),
            onDismiss = { showPreview = false }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

/**
 * Section "Mises à jour" du Settings. Affiche la version actuelle et un bouton
 * pour déclencher un check manuel. Le dialog MAJ (si une version est trouvée)
 * est rendu par [MainActivity.AppRoot] et apparaît automatiquement.
 */
@Composable
private fun UpdateSection() {
    val updateManager = ScannerApp.instance.updateManager
    val state by updateManager.state.collectAsStateWithLifecycle()
    val isBusy = state is UpdateState.Checking ||
        state is UpdateState.Downloading

    Text(
        stringResource(R.string.update_current_version, updateManager.currentVersion),
        fontSize = 14.sp,
        color = androidx.compose.ui.graphics.Color(0xFF4B5563)
    )
    Spacer(Modifier.height(8.dp))

    val statusText = when (state) {
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateState.Error -> (state as UpdateState.Error).message
        else -> null
    }
    if (statusText != null) {
        Text(
            statusText,
            fontSize = 12.sp,
            color = if (state is UpdateState.Error)
                androidx.compose.ui.graphics.Color(0xFFDC2626)
            else
                androidx.compose.ui.graphics.Color(0xFF059669),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = { updateManager.checkForUpdate(silent = false) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Text(
            stringResource(R.string.update_check_button),
            maxLines = 2,
            lineHeight = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
