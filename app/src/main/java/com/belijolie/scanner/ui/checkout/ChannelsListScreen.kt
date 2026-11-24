package com.belijolie.scanner.ui.checkout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.R
import com.belijolie.scanner.cart.Channel
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode
import kotlinx.coroutines.launch

/**
 * Écran de liste des canaux (nouveau point d'entrée de la caisse). En haut :
 * bouton « Créer un canal » qui crée + redirige vers son détail. En dessous :
 * la liste verticale de tous les canaux en cours (chaque carte navigue vers
 * le détail du canal au clic).
 *
 * Au montage de cet écran, on désactive le canal actif pour qu'un scan de
 * code-barres hors d'un détail n'alimente aucun panier (décision produit).
 */
@Composable
fun ChannelsListScreen(
    vm: CheckoutViewModel = viewModel(factory = CheckoutViewModel.Factory()),
    onOpenChannel: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val currentDevice by vm.currentDevice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var channelToRename by remember { mutableStateOf<Channel?>(null) }
    var channelToClose by remember { mutableStateOf<Channel?>(null) }
    // Verrou anti-spam sur le bouton "Créer un canal". Sans ce flag, taper
    // vite 3-4 fois lance autant de coroutines qui créent chacune un canal
    // + naviguent → le NavController finit par empiler plusieurs destinations
    // et l'écran affiché devient imprévisible (parfois un canal, parfois un
    // autre onglet si l'utilisateur touchait la bottom bar entre deux clics).
    var isCreating by remember { mutableStateOf(false) }

    // Mode sélection multiple (suppression de plusieurs canaux d'un coup).
    // Entrée : bouton « Sélectionner » ou appui long sur une carte.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    // Oublie les canaux disparus (supprimés ici ou sur un autre PDA)
    LaunchedEffect(state.channels) {
        val ids = state.channels.map { it.id }.toSet()
        selected = selected intersect ids
        if (ids.isEmpty()) selecting = false
    }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }

    LaunchedEffect(Unit) { vm.clearActiveChannel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            // Le wedge clavier du scanner Zebra envoie des key events parasites
            // (ENTER / autres caractères du barcode) qui peuvent voler le focus
            // Compose et faire sauter le premier tap sur une carte. On consomme
            // tout sauf BACK, comme sur ChannelDetailScreen.
            .onPreviewKeyEvent { event ->
                event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
            }
    ) {
        // Caisse ouverte + accès au choix de caisse
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    currentDevice?.name.orEmpty(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                com.belijolie.scanner.sync.SyncStatusChip()
            }
            TextButton(onClick = { vm.leaveDevice() }) {
                Text(stringResource(R.string.device_switch), fontSize = 12.sp)
            }
        }

        if (selecting) {
            // Barre de sélection : remplace le bouton « Créer un canal »
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .height(48.dp),
            ) {
                IconButton(onClick = { selecting = false; selected = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel), tint = Color(0xFF374151))
                }
                TextButton(onClick = {
                    val all = state.channels.map { it.id }.toSet()
                    selected = if (selected == all) emptySet() else all
                }) { Text(stringResource(R.string.channel_select_all)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { confirmBulkDelete = true },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                ) {
                    Text(
                        stringResource(R.string.channel_delete_selected, selected.size),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                if (isCreating) return@Button
                isCreating = true
                scope.launch {
                    try {
                        val newId = vm.newChannelAndGetId()
                        onOpenChannel(newId)
                    } finally {
                        isCreating = false
                    }
                }
            },
            enabled = !isCreating,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
                .height(48.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.channel_create),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
        if (state.channels.isNotEmpty()) {
            OutlinedButton(
                onClick = { selecting = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .padding(end = 12.dp)
                    .height(48.dp),
            ) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.channel_select), modifier = Modifier.size(22.dp))
            }
        }
        }

        if (state.channels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.channel_list_empty),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.channels, key = { it.id }) { ch ->
                    ChannelCard(
                        channel = ch,
                        selecting = selecting,
                        selected = ch.id in selected,
                        onOpen = {
                            if (selecting) {
                                selected = if (ch.id in selected) selected - ch.id else selected + ch.id
                            } else onOpenChannel(ch.id)
                        },
                        onLongPress = {
                            selecting = true
                            selected = selected + ch.id
                        },
                        onRename = { channelToRename = ch },
                        // Confirmation obligatoire, même si le canal est vide :
                        // la suppression est définitive et un tap accidentel
                        // sur la hitbox rouge peut partir vite.
                        onClose = { channelToClose = ch },
                    )
                }
            }
        }
    }

    channelToRename?.let { channel ->
        var name by remember(channel.id) { mutableStateOf(channel.name) }
        AlertDialog(
            onDismissRequest = { channelToRename = null },
            title = { Text(stringResource(R.string.channel_rename_title)) },
            text = {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.common_name),
                    mode = KeyboardMode.TEXT,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameChannel(channel.id, name.ifBlank { channel.name })
                    channelToRename = null
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { channelToRename = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text(stringResource(R.string.channel_bulk_delete_title, selected.size)) },
            text = { Text(stringResource(R.string.channel_bulk_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.closeChannels(selected)
                        confirmBulkDelete = false
                        selecting = false
                        selected = emptySet()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626)),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    channelToClose?.let { channel ->
        AlertDialog(
            onDismissRequest = { channelToClose = null },
            title = { Text(stringResource(R.string.channel_close_title)) },
            text = { Text(stringResource(R.string.channel_close_body, channel.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeChannel(channel.id)
                    channelToClose = null
                }) { Text(stringResource(R.string.common_close)) }
            },
            dismissButton = {
                TextButton(onClick = { channelToClose = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChannelCard(
    channel: Channel,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    val itemsCount = channel.lines.sumOf { it.quantity }
    val totalHt = channel.totalCentsAfterDiscount
    val totalTva = Math.round(totalHt * 0.20).toInt()
    val totalTtc = totalHt + totalTva

    // clickable sur la Row externe → le ripple d'ouverture couvre toute la
    // carte (y compris derrière les Box d'action). Les Box enfants ont leur
    // propre `clickable` qui consomme leur tap avant qu'il remonte au
    // parent → simple clic sur elles, simple clic sur le reste.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFFFEE2E2) else Color.White)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onOpen() },
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFDC2626)),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF111827),
                )
                if (channel.discountPercent > 0) {
                    Spacer(Modifier.width(6.dp))
                    DiscountBadge(channel.discountPercent)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.channel_items_count, itemsCount),
                fontSize = 12.sp,
                color = Color(0xFF6B7280),
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = 8.dp),
        ) {
            Text(
                formatEuro(totalTtc),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
            )
            Text(
                "TTC",
                fontSize = 10.sp,
                color = Color(0xFF6B7280),
            )
        }
        // En mode sélection : pas de boutons modifier / supprimer par carte
        if (selecting) return@Row
        // Hitbox "modifier" : clickable isolé, consomme son tap.
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE5E7EB))
                .clickable(onClick = onRename),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.common_edit),
                tint = Color(0xFF374151),
                modifier = Modifier.size(20.dp),
            )
        }
        // Hitbox "supprimer".
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFFEE2E2))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = Color(0xFFB91C1C),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DiscountBadge(percent: Int) {
    Box(
        modifier = Modifier
            .background(Color(0xFFDCFCE7), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "-$percent%",
            color = Color(0xFF166534),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
