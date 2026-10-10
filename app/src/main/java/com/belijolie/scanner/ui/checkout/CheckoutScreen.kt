package com.belijolie.scanner.ui.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.cart.Channel
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    vm: CheckoutViewModel = viewModel(factory = CheckoutViewModel.Factory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val unknownBarcode by vm.unknownBarcode.collectAsStateWithLifecycle()
    val printResult by vm.printResult.collectAsStateWithLifecycle()
    val isPrinting by vm.isPrinting.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(printResult) {
        printResult?.let {
            snackbar.showSnackbar(it.message)
            vm.clearPrintResult()
        }
    }

    val activeChannel = state.channels.firstOrNull { it.id == state.activeChannelId }
    val categories by vm.categories.collectAsStateWithLifecycle()

    var channelToRename by remember { mutableStateOf<Channel?>(null) }
    var channelToClose by remember { mutableStateOf<Channel?>(null) }
    var showAddManual by remember { mutableStateOf(false) }
    var showPrintConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Block hardware key events (scanner's keyboard-wedge trailing chars)
            // from activating any focused Button/Surface on this screen.
            .onPreviewKeyEvent { event ->
                event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
            }
    ) {

        ChannelDropdown(
            channels = state.channels,
            activeId = state.activeChannelId,
            onSelect = vm::selectChannel,
            onNew = vm::newChannel,
            onRename = { channelToRename = it },
            onClose = { ch ->
                // Empty cart → close immediately. Non-empty → ask for confirmation.
                if (ch.lines.isEmpty()) vm.closeChannel(ch.id)
                else channelToClose = ch
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Button(
                onClick = { showAddManual = true },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth().height(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ajouter un produit", color = Color.White, fontSize = 13.sp)
            }
        }

        // Compact column header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Catégorie", modifier = Modifier.weight(2.5f), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
            Text("Qté", modifier = Modifier.weight(0.6f), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
            Text("Total HT", modifier = Modifier.weight(1.1f), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(32.dp))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (activeChannel == null || activeChannel.lines.isEmpty()) {
                Text(
                    "Scanne un produit avec le bouton latéral.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = Color.Gray
                )
            } else {
                val sortedLines = sortLines(activeChannel.lines)
                val categoryIndexByName = buildMap<String, Int> {
                    var idx = -1
                    var last: String? = null
                    sortedLines.forEach {
                        if (it.categoryName != last) { idx++; last = it.categoryName }
                        put(it.categoryName, idx)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    items(sortedLines, key = { it.productId }) { line ->
                        val bold = (categoryIndexByName[line.categoryName] ?: 0) % 2 == 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                line.categoryName,
                                modifier = Modifier.weight(2.5f),
                                fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                line.quantity.toString(),
                                modifier = Modifier.weight(0.6f),
                                fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                formatEuro(line.unitPriceCents * line.quantity),
                                modifier = Modifier.weight(1.1f),
                                fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            IconButton(
                                onClick = { vm.removeLine(line.productId) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Retirer", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        val totalHtC = activeChannel?.totalCents ?: 0
        val totalTvaC = Math.round(totalHtC * 0.20).toInt()
        val totalTtcC = totalHtC + totalTvaC
        val totalQtyC = activeChannel?.lines?.sumOf { it.quantity } ?: 0

        TotalBar(
            totalQty = totalQtyC,
            totalHtCents = totalHtC,
            totalTvaCents = totalTvaC,
            totalTtcCents = totalTtcC,
            canPrint = activeChannel != null && activeChannel.lines.isNotEmpty() && !isPrinting,
            isPrinting = isPrinting,
            onPrint = { showPrintConfirm = true }
        )

        SnackbarHost(hostState = snackbar) { data -> Snackbar(snackbarData = data) }
    }

    val existingProducts by vm.existingProducts.collectAsStateWithLifecycle()

    if (showAddManual) {
        AddManualProductSheet(
            categories = categories,
            onDismiss = { showAddManual = false },
            onConfirm = { categoryName, cents ->
                vm.addManualToCart(categoryName, cents)
                showAddManual = false
            }
        )
    }

    unknownBarcode?.let { bc ->
        UnknownProductSheet(
            barcode = bc,
            categories = categories,
            existingProducts = existingProducts,
            onDismiss = vm::dismissUnknown,
            onCreate = { barcode, categoryId, cents ->
                vm.createAndAddToCart(barcode, categoryId, cents)
            },
            onPick = { productId -> vm.addExistingToCart(productId) },
            onCreateCategory = { name, onCreated -> vm.createCategory(name, onCreated) }
        )
    }

    channelToRename?.let { channel ->
        var name by remember(channel.id) { mutableStateOf(channel.name) }
        AlertDialog(
            onDismissRequest = { channelToRename = null },
            title = { Text("Renommer le canal") },
            text = {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Nom",
                    mode = KeyboardMode.TEXT
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameChannel(channel.id, name.ifBlank { channel.name })
                    channelToRename = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { channelToRename = null }) { Text("Annuler") }
            }
        )
    }

    channelToClose?.let { channel ->
        AlertDialog(
            onDismissRequest = { channelToClose = null },
            title = { Text("Fermer ce canal ?") },
            text = { Text("Le panier \"${channel.name}\" sera perdu.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeChannel(channel.id)
                    channelToClose = null
                }) { Text("Fermer") }
            },
            dismissButton = {
                TextButton(onClick = { channelToClose = null }) { Text("Annuler") }
            }
        )
    }

    if (showPrintConfirm) {
        AlertDialog(
            onDismissRequest = { showPrintConfirm = false },
            title = { Text("Imprimer le ticket ?") },
            text = { Text("Un ticket va être envoyé à l'imprimante. Confirmer ?") },
            confirmButton = {
                TextButton(onClick = {
                    showPrintConfirm = false
                    vm.printActive()
                }) { Text("Imprimer") }
            },
            dismissButton = {
                TextButton(onClick = { showPrintConfirm = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun ChannelDropdown(
    channels: List<Channel>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (Channel) -> Unit,
    onClose: (Channel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val active = channels.firstOrNull { it.id == activeId }
    val activeLabel = active?.name ?: "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        activeLabel,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("▼", color = Color.White, fontSize = 12.sp)
                }
            }

            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (channels.isEmpty()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Aucun canal — crée-en un →", color = Color.Gray) },
                        onClick = { expanded = false }
                    )
                }
                channels.forEach { ch ->
                    val isActive = ch.id == activeId
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ch.name,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "(${ch.lines.sumOf { it.quantity }})",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        trailingIcon = {
                            Row {
                                IconButton(
                                    onClick = {
                                        expanded = false
                                        onRename(ch)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("✎", fontSize = 14.sp)
                                }
                                IconButton(
                                    onClick = {
                                        // Keep the dropdown open so the user
                                        // can close several canals in a row.
                                        onClose(ch)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Fermer",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(ch.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.width(6.dp))

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF16A34A),
            modifier = Modifier.clickable(onClick = onNew)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("Canal", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TotalBar(
    totalQty: Int,
    totalHtCents: Int,
    totalTvaCents: Int,
    totalTtcCents: Int,
    canPrint: Boolean,
    isPrinting: Boolean,
    onPrint: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            TotalRow("Quantité totale", totalQty.toString(), bold = false, big = false)
            TotalRow("Total HT", formatEuro(totalHtCents), bold = false, big = false)
            TotalRow("TVA (20%)", formatEuro(totalTvaCents), bold = false, big = false)
            TotalRow("Total TTC", formatEuro(totalTtcCents), bold = true, big = true)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPrint,
                enabled = canPrint,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Impression…", fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Imprimer le ticket", fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean, big: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = if (big) 16.sp else 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
        Text(
            value,
            fontSize = if (big) 20.sp else 14.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

internal fun formatEuro(cents: Int): String {
    val euros = cents / 100
    val remainder = cents % 100
    return "%d,%02d €".format(euros, remainder)
}
