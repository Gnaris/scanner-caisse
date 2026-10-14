package com.belijolie.scanner.sync

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.belijolie.scanner.R
import com.belijolie.scanner.ScannerApp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.launch

/**
 * Dialog plein écran : viewfinder caméra en haut, barre de progression +
 * contrôles en bas. Collecte les segments QR, détecte complétion → écran de
 * confirmation avec résumé → import DB via [SyncManager].
 */
@Composable
fun SyncImportDialog(
    onDismiss: () -> Unit,
    onImported: (ImportResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val collector = remember { SegmentCollector() }
    var progress by remember { mutableStateOf(0 to 0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingSnapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var importing by remember { mutableStateOf(false) }
    var fatalError by remember { mutableStateOf<String?>(null) }

    val onSegmentScanned: (String) -> Unit = { raw ->
        if (pendingSnapshot == null && !importing) {
            when (val outcome = collector.receive(raw)) {
                is SegmentCollector.Outcome.Accepted -> {
                    feedback = context.getString(
                        R.string.sync_import_segment_ok, outcome.index, outcome.total
                    )
                }
                is SegmentCollector.Outcome.Duplicate -> {
                    feedback = context.getString(R.string.sync_import_segment_dup, outcome.index)
                }
                is SegmentCollector.Outcome.NewExportStarted -> {
                    feedback = context.getString(R.string.sync_import_new_export)
                }
                SegmentCollector.Outcome.ForeignQr -> {
                    feedback = context.getString(R.string.sync_import_foreign)
                }
            }
            progress = collector.progress
            if (collector.isComplete) {
                runCatching { QrCodec.decodeFromSegments(collector.collectedSegments()) }
                    .onSuccess { pendingSnapshot = it }
                    .onFailure {
                        fatalError = it.message ?: "Erreur de décodage"
                        collector.reset()
                        progress = 0 to 0
                    }
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = pendingSnapshot == null,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when {
                pendingSnapshot != null -> ConfirmPanel(
                    snapshot = pendingSnapshot!!,
                    importing = importing,
                    onCancel = {
                        pendingSnapshot = null
                        collector.reset()
                        progress = 0 to 0
                        feedback = null
                    },
                    onConfirm = {
                        importing = true
                        scope.launch {
                            val result = runCatching {
                                SyncManager(
                                    ScannerApp.instance.db,
                                    ScannerApp.instance.settingsStore,
                                ).importSnapshot(pendingSnapshot!!)
                            }
                            importing = false
                            result.onSuccess {
                                onImported(it)
                                onDismiss()
                            }.onFailure { fatalError = it.message ?: "Erreur import" }
                        }
                    },
                )
                !hasCameraPermission -> PermissionPanel(
                    onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onDismiss = onDismiss,
                )
                else -> ScanPanel(
                    progress = progress,
                    feedback = feedback,
                    onSegmentScanned = onSegmentScanned,
                    onDismiss = onDismiss,
                )
            }

            fatalError?.let { msg ->
                ErrorOverlay(msg) { fatalError = null }
            }
        }
    }
}

@Composable
private fun ScanPanel(
    progress: Pair<Int, Int>,
    feedback: String?,
    onSegmentScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val latestOnScan by rememberUpdatedState(onSegmentScanned)

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            // Pas de texte "scanner" en bas du viewfinder : on gère nous-mêmes
            // les labels dans la Column par-dessus.
            setStatusText("")
        }
    }

    DisposableEffect(Unit) {
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val raw = result?.text ?: return
                latestOnScan(raw)
                // Debounce : pause 600ms puis resume, pour éviter de re-scanner
                // 10× le même QR en 1 seconde.
                barcodeView.pause()
                barcodeView.postDelayed({ barcodeView.resume() }, 600)
            }
            override fun possibleResultPoints(points: List<com.google.zxing.ResultPoint>?) {}
        }
        barcodeView.decodeContinuous(callback)
        barcodeView.resume()
        onDispose { barcodeView.pause() }
    }

    AndroidView(
        factory = { barcodeView },
        modifier = Modifier.fillMaxSize(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sync_import_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val (done, total) = progress
                Text(
                    if (total > 0)
                        stringResource(R.string.sync_import_progress, done, total)
                    else
                        stringResource(R.string.sync_import_waiting),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (total > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = done.toFloat() / total.toFloat(),
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF4F46E5),
                        trackColor = Color(0xFF374151),
                    )
                }
                feedback?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFD1D5DB), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ConfirmPanel(
    snapshot: CatalogSnapshot,
    importing: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF059669),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sync_confirm_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    R.string.sync_confirm_body,
                    snapshot.categories.size,
                    snapshot.products.size,
                ),
                fontSize = 14.sp,
                color = Color(0xFF4B5563),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !importing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = !importing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(20.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.sync_confirm_import),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPanel(onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFF4F46E5))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sync_perm_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sync_perm_body),
                fontSize = 14.sp,
                color = Color(0xFF4B5563),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.sync_perm_grant), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.common_error),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFDC2626),
                )
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 14.sp, color = Color(0xFF4B5563))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}
