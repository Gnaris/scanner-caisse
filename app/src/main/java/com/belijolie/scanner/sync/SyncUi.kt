package com.belijolie.scanner.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.belijolie.scanner.R
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Couleur + libellé d'un état de synchro. */
@Composable
private fun statusLook(status: SyncStatus): Pair<Color, String> = when (status) {
    SyncStatus.NotConfigured -> Color(0xFFF59E0B) to stringResource(R.string.sync_status_not_configured)
    SyncStatus.Offline -> Color(0xFF9CA3AF) to stringResource(R.string.sync_status_offline)
    SyncStatus.Syncing -> Color(0xFF3B82F6) to stringResource(R.string.sync_status_syncing)
    is SyncStatus.Synced -> Color(0xFF16A34A) to stringResource(R.string.sync_status_ok)
    is SyncStatus.Error -> Color(0xFFDC2626) to stringResource(R.string.sync_status_error)
}

/**
 * Petite pastille d'état (« Synchronisé », « Hors-ligne »…). Un appui
 * relance une synchronisation.
 */
@Composable
fun SyncStatusChip(modifier: Modifier = Modifier) {
    val engine = ScannerApp.instance.syncEngine
    val status by engine.status.collectAsStateWithLifecycle()
    val (color, label) = statusLook(status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable { engine.requestSync() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF374151), maxLines = 1)
    }
}

/** Bloc complet pour l'écran Paramètres. */
@Composable
fun SyncSettingsSection() {
    val engine = ScannerApp.instance.syncEngine
    val status by engine.status.collectAsStateWithLifecycle()
    var showConfig by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sync_hint), fontSize = 12.sp, color = Color(0xFF6B7280))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncStatusChip()
            (status as? SyncStatus.Synced)?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.sync_last, SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(it.atMs))),
                    fontSize = 11.sp,
                    color = Color(0xFF6B7280),
                )
            }
        }
        (status as? SyncStatus.Error)?.let {
            Text(it.message, fontSize = 12.sp, color = Color(0xFFDC2626))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = { showConfig = true },
                modifier = Modifier.weight(1f).height(60.dp),
            ) {
                Text(
                    stringResource(R.string.sync_configure),
                    fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            androidx.compose.material3.Button(
                onClick = { engine.requestSync() },
                modifier = Modifier.weight(1f).height(60.dp),
            ) {
                Text(
                    stringResource(R.string.sync_now),
                    fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
    if (showConfig) SyncConfigDialog(onDismiss = { showConfig = false })
}

/**
 * Saisie manuelle de l'adresse du serveur et de la clé. Le plus simple
 * reste de scanner le QR de configuration (géré globalement par ScannerApp),
 * ce qui ferme aussi ce dialogue au prochain affichage.
 */
@Composable
fun SyncConfigDialog(onDismiss: () -> Unit) {
    val engine = ScannerApp.instance.syncEngine
    val scope = rememberCoroutineScope()
    val cfg by engine.config.collectAsStateWithLifecycle(initialValue = null)
    val current = cfg ?: return
    var url by remember(current.serverUrl) { mutableStateOf(current.serverUrl) }
    // La clé n'est jamais réaffichée en entier : champ vide = garder l'actuelle.
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_configure), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.sync_scan_hint), fontSize = 13.sp, color = Color(0xFF4F46E5))
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.sync_url_label),
                    mode = KeyboardMode.TEXT,
                )
                AppTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = stringResource(R.string.sync_key_label),
                    mode = KeyboardMode.TEXT,
                    placeholder = if (current.apiKey.isNotBlank())
                        stringResource(R.string.sync_key_set, current.apiKey.takeLast(4)) else "",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    engine.setConfig(url, key.ifBlank { current.apiKey })
                    onDismiss()
                }
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
