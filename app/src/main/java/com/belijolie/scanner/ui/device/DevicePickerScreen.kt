package com.belijolie.scanner.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.R
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.data.AppDatabase
import com.belijolie.scanner.data.DeviceSession
import com.belijolie.scanner.data.entity.Device
import com.belijolie.scanner.sync.SyncConfigDialog
import com.belijolie.scanner.sync.SyncStatusChip
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicePickerViewModel(
    private val session: DeviceSession,
    private val db: AppDatabase,
) : ViewModel() {

    val devices: StateFlow<List<Device>?> = session.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun add(name: String): Device = session.addDevice(name)

    suspend fun openChannelsCount(deviceId: String): Int =
        db.channelDao().getForDevice(deviceId).size

    fun delete(deviceId: String) {
        viewModelScope.launch { session.deleteDevice(deviceId) }
    }

    fun open(deviceId: String) {
        viewModelScope.launch { session.select(deviceId) }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = ScannerApp.instance
            @Suppress("UNCHECKED_CAST")
            return DevicePickerViewModel(app.session, app.db) as T
        }
    }
}

/**
 * Écran affiché tant qu'aucune caisse n'est ouverte sur ce PDA : liste
 * déroulante des caisses + Ouvrir / Ajouter / Supprimer. Pas de mot de
 * passe, c'est juste le choix de la caisse dont on veut voir les canaux.
 */
@Composable
fun DevicePickerScreen(
    vm: DevicePickerViewModel = viewModel(factory = DevicePickerViewModel.Factory()),
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val list = devices.orEmpty()
    val scope = rememberCoroutineScope()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Pair<Device, Int>?>(null) }
    var showSyncConfig by remember { mutableStateOf(false) }

    // Sélection par défaut : la seule caisse s'il n'y en a qu'une ; on oublie
    // la sélection si la caisse disparaît (supprimée ici ou ailleurs).
    LaunchedEffect(list) {
        if (selectedId != null && list.none { it.id == selectedId }) selectedId = null
        if (selectedId == null && list.size == 1) selectedId = list.first().id
    }
    val selected = list.firstOrNull { it.id == selectedId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            // Touches parasites du wedge scanner : on consomme tout sauf BACK.
            .onPreviewKeyEvent { it.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Default.PointOfSale,
                contentDescription = null,
                tint = Color(0xFF4F46E5),
                modifier = Modifier.size(48.dp),
            )
            Text(stringResource(R.string.device_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.device_hint),
                fontSize = 13.sp,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center,
            )

            if (devices != null && list.isEmpty()) {
                Text(
                    stringResource(R.string.device_none),
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text(
                            selected?.name ?: stringResource(R.string.device_choose),
                            fontSize = 17.sp,
                            fontWeight = if (selected != null) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        list.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.name, fontSize = 16.sp) },
                                onClick = { selectedId = d.id; menuOpen = false },
                            )
                        }
                    }
                }

                Button(
                    onClick = { selected?.let { vm.open(it.id) } },
                    enabled = selected != null,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.device_open), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.device_add)) }
                OutlinedButton(
                    onClick = {
                        val d = selected ?: return@OutlinedButton
                        scope.launch { toDelete = d to vm.openChannelsCount(d.id) }
                    },
                    enabled = selected != null,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.device_delete)) }
            }

            // Synchro : un PDA neuf doit d'abord la configurer pour récupérer
            // les caisses existantes (scanner le QR de configuration suffit).
            Spacer(Modifier.height(8.dp))
            SyncStatusChip()
            TextButton(onClick = { showSyncConfig = true }) {
                Text(stringResource(R.string.sync_configure), fontSize = 13.sp)
            }
        }
    }

    if (showSyncConfig) SyncConfigDialog(onDismiss = { showSyncConfig = false })

    if (showAdd) {
        val suggestion = stringResource(R.string.device_default_name, nextNumber(list))
        var name by remember { mutableStateOf(suggestion) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.device_add)) },
            text = {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.device_name_label),
                    mode = KeyboardMode.TEXT,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleaned = name.trim()
                        if (cleaned.isEmpty()) return@TextButton
                        scope.launch {
                            selectedId = vm.add(cleaned).id
                            showAdd = false
                        }
                    },
                ) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    toDelete?.let { (device, count) ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.device_delete_title, device.name)) },
            text = { if (count > 0) Text(stringResource(R.string.device_delete_body, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(device.id)
                        toDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626)),
                ) { Text(stringResource(R.string.device_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Plus petit N tel que « Appareil N » n'existe pas encore. */
private fun nextNumber(devices: List<Device>): Int {
    val taken = devices.mapNotNull { Regex("""(?i)^appareil\s+(\d+)$""").find(it.name.trim())?.groupValues?.get(1)?.toIntOrNull() }.toSet()
    var n = 1
    while (n in taken) n++
    return n
}
