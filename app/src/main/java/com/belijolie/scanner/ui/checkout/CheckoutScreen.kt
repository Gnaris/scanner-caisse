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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belijolie.scanner.R
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

/**
 * Écran détail d'un canal : table des lignes du panier, totaux, boutons
 * encaissement / export / impression. Dérivé de l'ancien CheckoutScreen
 * (sans le dropdown de switch de canal, qui vit maintenant dans
 * [ChannelsListScreen]).
 *
 * Au montage, on positionne [channelId] comme canal actif pour que les
 * scans et toutes les mutations repo tapent le bon canal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    channelId: String,
    onBack: () -> Unit,
    vm: CheckoutViewModel = viewModel(factory = CheckoutViewModel.Factory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val unknownBarcode by vm.unknownBarcode.collectAsStateWithLifecycle()
    val printResult by vm.printResult.collectAsStateWithLifecycle()
    val isPrinting by vm.isPrinting.collectAsStateWithLifecycle()
    val exportResult by vm.exportResult.collectAsStateWithLifecycle()
    val isExporting by vm.isExporting.collectAsStateWithLifecycle()

    LaunchedEffect(channelId) { vm.selectChannel(channelId) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(printResult) {
        printResult?.let {
            snackbar.showSnackbar(it.message)
            vm.clearPrintResult()
        }
    }
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

    var showAddManual by remember { mutableStateOf(false) }
    var showPrintConfirm by remember { mutableStateOf(false) }
    var showCashPayment by remember { mutableStateOf(false) }
    var showExportFormat by remember { mutableStateOf(false) }
    var showDiscountDialog by remember { mutableStateOf(false) }
    var lineToRemove by remember { mutableStateOf<com.belijolie.scanner.cart.CartLine?>(null) }
    var lineToEdit by remember { mutableStateOf<com.belijolie.scanner.cart.CartLine?>(null) }

    val discount = activeChannel?.discountPercent ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
            }
    ) {

        val scanMultiplier by vm.scanMultiplier.collectAsStateWithLifecycle()
        var showMultiplierDialog by remember { mutableStateOf(false) }

        // Barre du haut : [← retour] [+ produit] [×1]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFE5E7EB),
                modifier = Modifier
                    .size(width = 44.dp, height = 34.dp)
                    .clickable(onClick = onBack),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.channel_back),
                        tint = Color(0xFF374151),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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

        // Nom du canal + badge remise sous la barre d'actions
        activeChannel?.let { ch ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ch.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF374151),
                    modifier = Modifier.weight(1f),
                )
                if (discount > 0) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFDCFCE7), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "-$discount%",
                            color = Color(0xFF166534),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.checkout_header_category), modifier = Modifier.weight(2.2f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2)
            Text(stringResource(R.string.checkout_header_qty), modifier = Modifier.weight(0.6f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2)
            Text(stringResource(R.string.checkout_header_unit_price), modifier = Modifier.weight(1.0f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text(stringResource(R.string.checkout_header_total_ht), modifier = Modifier.weight(1.2f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Spacer(Modifier.width(42.dp))
        }

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
                    items(sortedLines, key = { "${it.productId}_${it.categoryName}_${it.unitPriceCents}" }) { line ->
                        val bold = (categoryIndexByName[line.categoryName] ?: 0) % 2 == 1
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
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
                                    modifier = Modifier.weight(2.2f),
                                    fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "x${line.quantity}",
                                    modifier = Modifier.weight(0.6f),
                                    fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                // P.U. : prix unitaire brut, PAS impacté par la
                                // remise (la remise porte sur le total ligne,
                                // afficher un P.U. remisé serait trompeur sur
                                // le prix réel de l'article).
                                Text(
                                    formatEuro(line.unitPriceCents),
                                    modifier = Modifier.weight(1.0f),
                                    fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                                LineAmount(
                                    rawCents = line.unitPriceCents * line.quantity,
                                    discountPercent = discount,
                                    bold = bold,
                                    modifier = Modifier.weight(1.2f),
                                )
                                Spacer(Modifier.width(4.dp))
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

        val totalHtRaw = activeChannel?.totalCents ?: 0
        val discountCents = if (discount <= 0) 0 else Math.round(totalHtRaw * discount / 100.0).toInt()
        val totalHtC = totalHtRaw - discountCents
        val totalTvaC = Math.round(totalHtC * 0.20).toInt()
        val totalTtcC = totalHtC + totalTvaC
        val totalQtyC = activeChannel?.lines?.sumOf { it.quantity } ?: 0

        TotalBar(
            totalQty = totalQtyC,
            totalHtRawCents = totalHtRaw,
            totalHtCents = totalHtC,
            totalTvaCents = totalTvaC,
            totalTtcCents = totalTtcC,
            discountPercent = discount,
            discountCents = discountCents,
            onDiscount = { showDiscountDialog = true },
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
            onConfirm = { categoryName, cents, qty, nameEn ->
                vm.addManualToCart(categoryName, cents, qty, nameEn)
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
            totalHtCents = activeChannel?.totalCentsAfterDiscount ?: 0,
            channelName = activeChannel?.name.orEmpty(),
            onDismiss = { showCashPayment = false },
            onCloseChannel = {
                val id = activeChannel?.id
                showCashPayment = false
                if (id != null) {
                    vm.closeChannel(id)
                    onBack()
                }
            }
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

    if (showDiscountDialog && activeChannel != null) {
        DiscountDialog(
            current = discount,
            onDismiss = { showDiscountDialog = false },
            onConfirm = {
                vm.setDiscount(it)
                showDiscountDialog = false
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

/**
 * Montant d'une ligne. Si une remise canal est active, affiche le prix brut
 * barré (gris) suivi du prix remisé en vert. Sinon affiche juste le prix.
 */
@Composable
private fun LineAmount(
    rawCents: Int,
    discountPercent: Int,
    bold: Boolean,
    modifier: Modifier = Modifier,
) {
    if (discountPercent > 0) {
        val discounted = rawCents - Math.round(rawCents * discountPercent / 100.0).toInt()
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                formatEuro(rawCents),
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF),
                textDecoration = TextDecoration.LineThrough,
                fontWeight = FontWeight.Medium,
            )
            Text(
                formatEuro(discounted),
                fontSize = 14.sp,
                color = Color(0xFF15803D),
                fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
            )
        }
    } else {
        Text(
            formatEuro(rawCents),
            modifier = modifier,
            fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun TotalBar(
    totalQty: Int,
    totalHtRawCents: Int,
    totalHtCents: Int,
    totalTvaCents: Int,
    totalTtcCents: Int,
    discountPercent: Int,
    discountCents: Int,
    onDiscount: () -> Unit,
    canPrint: Boolean,
    isPrinting: Boolean,
    onPrint: () -> Unit,
    canCash: Boolean,
    onCashPayment: () -> Unit,
    canExport: Boolean,
    isExporting: Boolean,
    onExport: () -> Unit
) {
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
                if (discountPercent > 0) {
                    TotalRow(
                        label = stringResource(R.string.checkout_total_ht),
                        value = formatEuro(totalHtRawCents),
                        bold = false,
                        big = false,
                        strike = true,
                        color = Color(0xFF9CA3AF),
                    )
                    TotalRow(
                        label = stringResource(R.string.discount_line_label, discountPercent),
                        value = "-" + formatEuro(discountCents),
                        bold = true,
                        big = false,
                        color = Color(0xFF15803D),
                    )
                    TotalRow(
                        label = stringResource(R.string.checkout_total_ht),
                        value = formatEuro(totalHtCents),
                        bold = true,
                        big = false,
                        color = Color(0xFF15803D),
                    )
                } else {
                    TotalRow(stringResource(R.string.checkout_total_ht), formatEuro(totalHtCents), bold = false, big = false)
                }
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
                    // Bouton remise. Vert clair si active, violet neutre sinon.
                    Button(
                        onClick = onDiscount,
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (discountPercent > 0) Color(0xFF15803D) else Color(0xFF7C3AED)
                        )
                    ) {
                        Icon(
                            Icons.Default.Percent,
                            contentDescription = stringResource(R.string.discount_button),
                            modifier = Modifier.size(18.dp)
                        )
                        if (discountPercent > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$discountPercent",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
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
            } }
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    value: String,
    bold: Boolean,
    big: Boolean,
    strike: Boolean = false,
    color: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = if (big) 16.sp else 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = color,
            textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
        )
        Text(
            value,
            fontSize = if (big) 20.sp else 14.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = color,
            textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}

internal fun formatEuro(cents: Int): String {
    val euros = cents / 100
    val remainder = cents % 100
    return "%d,%02d €".format(euros, remainder)
}

/**
 * Dialog d'édition d'une ligne existante du panier.
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
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
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
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
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

/**
 * Dialog de saisie d'une remise en pourcentage (0-100). La remise ne touche
 * que l'affichage caisse et le ticket imprimé — l'export CSV / JSON / Sage
 * reste au prix brut (décision produit).
 */
@Composable
private fun DiscountDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(if (current == 0) "" else current.toString()) }
    val parsed = text.toIntOrNull()?.coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discount_dialog_title)) },
        text = {
            Column {
                AppTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = stringResource(R.string.discount_percent_label),
                    mode = KeyboardMode.NUMBER,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.discount_dialog_hint),
                    fontSize = 12.sp,
                    color = Color.Gray,
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
