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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.draw.rotate
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    vm: CheckoutViewModel = viewModel(factory = CheckoutViewModel.Factory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val unknownBarcode by vm.unknownBarcode.collectAsStateWithLifecycle()
    val printResult by vm.printResult.collectAsStateWithLifecycle()
    val isPrinting by vm.isPrinting.collectAsStateWithLifecycle()
    val exportResult by vm.exportResult.collectAsStateWithLifecycle()
    val isExporting by vm.isExporting.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(printResult) {
        printResult?.let {
            snackbar.showSnackbar(it.message)
            vm.clearPrintResult()
        }
    }
    // Erreur d'export : on l'affiche dans un AlertDialog (message potentiellement
    // long, ex. liste des catégories sans code Sage). Les succès restent en
    // snackbar discret.
    var exportErrorDialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exportResult) {
        exportResult?.let { r ->
            if (r.success) {
                snackbar.showSnackbar(r.message)
            } else {
                exportErrorDialog = r.message
            }
            vm.clearExportResult()
        }
    }
    exportErrorDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportErrorDialog = null },
            title = { Text(stringResource(R.string.export_error_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { exportErrorDialog = null }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }

    val activeChannel = state.channels.firstOrNull { it.id == state.activeChannelId }
    val categories by vm.categories.collectAsStateWithLifecycle()

    var channelToRename by remember { mutableStateOf<Channel?>(null) }
    var channelToClose by remember { mutableStateOf<Channel?>(null) }
    var showAddManual by remember { mutableStateOf(false) }
    var showPrintConfirm by remember { mutableStateOf(false) }
    var showCashPayment by remember { mutableStateOf(false) }
    var showExportFormat by remember { mutableStateOf(false) }
    var lineToRemove by remember { mutableStateOf<com.belijolie.scanner.cart.CartLine?>(null) }
    var lineToEdit by remember { mutableStateOf<com.belijolie.scanner.cart.CartLine?>(null) }

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

        val scanMultiplier by vm.scanMultiplier.collectAsStateWithLifecycle()
        var showMultiplierDialog by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { showAddManual = true },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).height(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.checkout_add_product), color = Color.White, fontSize = 13.sp)
            }
            // Mini carré "quantité par scan". Click → ouvre un AlertDialog
            // avec clavier numérique pour éditer la valeur. Reset auto à 1
            // après chaque scan côté VM.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (scanMultiplier > 1) Color(0xFFF59E0B) else Color(0xFF374151),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { showMultiplierDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "×$scanMultiplier",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        if (showMultiplierDialog) {
            MultiplierDialog(
                current = scanMultiplier,
                onDismiss = { showMultiplierDialog = false },
                onConfirm = {
                    vm.setScanMultiplier(it)
                    showMultiplierDialog = false
                }
            )
        }

        // Mini bloc "dernier scan" — toujours au-dessus du header des colonnes.
        // Click → dialog d'édition catégorie / quantité / prix. Un nouveau scan
        // le remplace. Clear à l'impression ou au changement de canal.
        val lastScan by vm.lastScan.collectAsStateWithLifecycle()
        var showLastScanEdit by remember { mutableStateOf(false) }
        lastScan?.let { scan ->
            LastScanBlock(scan = scan, onClick = { showLastScanEdit = true })
        }
        if (showLastScanEdit && lastScan != null) {
            EditLastScanDialog(
                scan = lastScan!!,
                categories = categories,
                onDismiss = { showLastScanEdit = false },
                onConfirm = { newCategory, newPriceCents, newQty ->
                    vm.editLastScan(newCategory, newPriceCents, newQty)
                    showLastScanEdit = false
                }
            )
        }

        // Compact column header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.checkout_header_category), modifier = Modifier.weight(2.5f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2)
            Text(stringResource(R.string.checkout_header_qty), modifier = Modifier.weight(0.8f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2)
            Text(stringResource(R.string.checkout_header_total_ht), modifier = Modifier.weight(1.2f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2)
            Spacer(Modifier.width(32.dp))
        }

        // Zone panier : fond gris clair pour que les cartes blanches des lignes
        // ressortent nettement. Padding horizontal 8dp pour aérer les côtés.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFF3F4F6))
        ) {
            if (activeChannel == null || activeChannel.lines.isEmpty()) {
                Text(
                    stringResource(R.string.checkout_empty_hint),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(sortedLines, key = { it.productId }) { line ->
                        val bold = (categoryIndexByName[line.categoryName] ?: 0) % 2 == 1
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Click sur une ligne → dialog d'édition.
                                // Les sous-composants (poubelle) ont leur
                                // propre clickable qui consomme le tap.
                                .clickable { lineToEdit = line }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    line.categoryName,
                                    modifier = Modifier.weight(2.5f),
                                    fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "x${line.quantity}",
                                    modifier = Modifier.weight(0.8f),
                                    fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    formatEuro(line.unitPriceCents * line.quantity),
                                    modifier = Modifier.weight(1.2f),
                                    fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                // Hitbox visible pour la poubelle : carré rouge
                                // clair arrondi, plus facile à atteindre au doigt
                                // sur un PDA et confirmation requise pour éviter
                                // les suppressions accidentelles.
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFFEE2E2), RoundedCornerShape(8.dp))
                                        .clickable { lineToRemove = line },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.checkout_remove_line),
                                        tint = Color(0xFFB91C1C),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
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
            onPrint = { showPrintConfirm = true },
            canCash = activeChannel != null && activeChannel.lines.isNotEmpty(),
            onCashPayment = { showCashPayment = true },
            canExport = activeChannel != null && activeChannel.lines.isNotEmpty() && !isExporting,
            isExporting = isExporting,
            onExport = { showExportFormat = true }
        )

        SnackbarHost(hostState = snackbar) { data -> Snackbar(snackbarData = data) }
    }

    val existingProducts by vm.existingProducts.collectAsStateWithLifecycle()

    if (showAddManual) {
        AddManualProductSheet(
            categories = categories,
            onDismiss = { showAddManual = false },
            onConfirm = { categoryName, cents, nameEn ->
                vm.addManualToCart(categoryName, cents, nameEn)
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
            onCreateCategory = { name, nameEn, sage, onCreated ->
                vm.createCategory(name, nameEn, sage, onCreated)
            }
        )
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
                    mode = KeyboardMode.TEXT
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
            }
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
            }
        )
    }

    lineToRemove?.let { line ->
        AlertDialog(
            onDismissRequest = { lineToRemove = null },
            title = { Text(stringResource(R.string.checkout_remove_confirm_title)) },
            text = { Text(stringResource(R.string.checkout_remove_confirm_body, line.categoryName)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeLine(line.productId)
                    lineToRemove = null
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = Color(0xFFB91C1C),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { lineToRemove = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showCashPayment) {
        CashPaymentSheet(
            totalHtCents = activeChannel?.totalCents ?: 0,
            onDismiss = { showCashPayment = false }
        )
    }

    if (showExportFormat) {
        ExportFormatPickerDialog(
            onDismiss = { showExportFormat = false },
            onPick = { format ->
                showExportFormat = false
                activeChannel?.let { vm.exportChannel(it.id, format) }
            }
        )
    }

    if (showPrintConfirm) {
        PrintConfirmDialog(
            onDismiss = { showPrintConfirm = false },
            onConfirm = { lang ->
                showPrintConfirm = false
                vm.printActive(lang)
            }
        )
    }

    lineToEdit?.let { line ->
        EditCartLineDialog(
            line = line,
            categories = categories,
            onDismiss = { lineToEdit = null },
            onConfirm = { newCategory, newPrice, newQty ->
                vm.editLine(line.productId, newCategory, newPrice, newQty)
                lineToEdit = null
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
                        text = { Text(stringResource(R.string.channel_none), color = Color.Gray) },
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
                                        contentDescription = stringResource(R.string.common_close),
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
                Text(stringResource(R.string.channel_new), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
    onPrint: () -> Unit,
    canCash: Boolean,
    onCashPayment: () -> Unit,
    canExport: Boolean,
    isExporting: Boolean,
    onExport: () -> Unit
) {
    // Tiroir : par défaut déplié. Click sur le header (chevron) replie le
    // détail (lignes HT / TVA / Qté) et les boutons, ne laissant visible
    // que le total TTC compact. Animation expand/collapse 250ms.
    var expanded by remember { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "chevronRotation",
    )

    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.checkout_total_ttc),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatEuro(totalTtcCents),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(250)),
                exit = shrinkVertically(animationSpec = tween(250)),
            ) { Column {
            TotalRow(stringResource(R.string.checkout_total_quantity), "x$totalQty", bold = false, big = false)
            TotalRow(stringResource(R.string.checkout_total_ht), formatEuro(totalHtCents), bold = false, big = false)
            TotalRow(stringResource(R.string.checkout_total_vat), formatEuro(totalTvaCents), bold = false, big = false)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onCashPayment,
                    enabled = canCash,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Icon(
                        Icons.Default.AttachMoney,
                        contentDescription = stringResource(R.string.checkout_action_cash),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Button(
                    onClick = onExport,
                    enabled = canExport,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = stringResource(R.string.checkout_action_export),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Button(
                    onClick = onPrint,
                    enabled = canPrint,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (isPrinting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Print,
                            contentDescription = stringResource(R.string.checkout_action_print),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            } }  // fin Column inner + AnimatedVisibility
        }      // fin Column racine Surface
    }          // fin Surface
}              // fin TotalBar

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

/**
 * Dialog d'édition d'une ligne existante du panier. Même structure que
 * [EditLastScanDialog] mais sans hint "annule le dernier scan" (ici
 * l'édition est en-place, sans fusion/décalage).
 */
@Composable
private fun EditCartLineDialog(
    line: com.belijolie.scanner.cart.CartLine,
    categories: List<com.belijolie.scanner.data.entity.Category>,
    onDismiss: () -> Unit,
    onConfirm: (categoryName: String, priceCents: Int, quantity: Int) -> Unit,
) {
    var categoryName by remember { mutableStateOf(line.categoryName) }
    var qtyText by remember { mutableStateOf(line.quantity.toString()) }
    var priceText by remember {
        mutableStateOf("%d.%02d".format(line.unitPriceCents / 100, line.unitPriceCents % 100))
    }
    var expanded by remember { mutableStateOf(false) }

    val qty = qtyText.toIntOrNull()?.coerceAtLeast(1)
    val priceCents = run {
        val n = priceText.replace(',', '.').toDoubleOrNull()
        if (n == null || n < 0) null else (n * 100).toInt()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkout_line_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(stringResource(R.string.checkout_header_category), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text(categoryName.ifBlank { "—" }, fontSize = 15.sp)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { c ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = {
                                    categoryName = c.name
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                AppTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.checkout_quantity),
                    mode = KeyboardMode.NUMBER,
                )
                AppTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = stringResource(R.string.product_price),
                    mode = KeyboardMode.DECIMAL,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = categoryName.isNotBlank() && qty != null && priceCents != null,
                onClick = {
                    if (qty != null && priceCents != null) {
                        onConfirm(categoryName, priceCents, qty)
                    }
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/**
 * Mini bande affichant le dernier produit scanné (catégorie / quantité / prix
 * unitaire). Click → ouvre [EditLastScanDialog] pour corriger. Un nouveau
 * scan remplace cette bande. Clear à l'impression ou au changement de canal.
 */
@Composable
private fun LastScanBlock(
    scan: LastScan,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .height(36.dp)
            .clickable(onClick = onClick),
        color = Color(0xFFFFEDD5),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "✎",
                color = Color(0xFF9A3412),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                scan.categoryName,
                color = Color(0xFF7C2D12),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "×${scan.quantity}",
                color = Color(0xFF9A3412),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formatEuro(scan.priceCents),
                color = Color(0xFF7C2D12),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Dialog d'édition du dernier scan. Champs catégorie (dropdown), quantité,
 * prix. Confirmer → VM annule l'ancien ajout et rejoue avec les nouvelles
 * valeurs.
 */
@Composable
private fun EditLastScanDialog(
    scan: LastScan,
    categories: List<com.belijolie.scanner.data.entity.Category>,
    onDismiss: () -> Unit,
    onConfirm: (categoryName: String, priceCents: Int, quantity: Int) -> Unit,
) {
    var categoryName by remember { mutableStateOf(scan.categoryName) }
    var qtyText by remember { mutableStateOf(scan.quantity.toString()) }
    var priceText by remember {
        mutableStateOf("%d.%02d".format(scan.priceCents / 100, scan.priceCents % 100))
    }
    var expanded by remember { mutableStateOf(false) }

    val qty = qtyText.toIntOrNull()?.coerceAtLeast(1)
    val priceCents = run {
        val n = priceText.replace(',', '.').toDoubleOrNull()
        if (n == null || n < 0) null else (n * 100).toInt()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkout_last_scan_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.checkout_last_scan_hint),
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
                Column {
                    Text(stringResource(R.string.checkout_header_category), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text(categoryName.ifBlank { "—" }, fontSize = 15.sp)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { c ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = {
                                    categoryName = c.name
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                AppTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.checkout_quantity),
                    mode = KeyboardMode.NUMBER,
                )
                AppTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = stringResource(R.string.product_price),
                    mode = KeyboardMode.DECIMAL,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = categoryName.isNotBlank() && qty != null && priceCents != null,
                onClick = {
                    if (qty != null && priceCents != null) {
                        onConfirm(categoryName, priceCents, qty)
                    }
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/**
 * Dialog d'édition du multiplicateur de quantité par scan. Clavier NUMBER
 * pour saisie rapide, OK désactivé si le champ est vide.
 */
@Composable
private fun MultiplierDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()?.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkout_qty_mult_label)) },
        text = {
            Column {
                AppTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.checkout_qty_mult_label),
                    mode = KeyboardMode.NUMBER
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.checkout_qty_mult_hint),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onConfirm(it) } }
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
