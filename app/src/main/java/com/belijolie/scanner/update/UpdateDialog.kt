package com.belijolie.scanner.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.R
import com.belijolie.scanner.ScannerApp

/**
 * Dialog unique couvrant les 4 états "visibles" de l'updater :
 * [UpdateState.Available], [UpdateState.Downloading], [UpdateState.ReadyToInstall]
 * et [UpdateState.Error]. L'UI reste rendue par le même [Surface] pour éviter
 * les flashs entre les transitions.
 *
 * [onDismiss] est appelé pour les états où l'utilisateur peut encore refuser
 * (Available, Error). Pendant Downloading / ReadyToInstall on verrouille la
 * fermeture par geste (dismissOnBackPress / dismissOnClickOutside = false)
 * pour éviter qu'une MAJ en cours soit abandonnée accidentellement.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
) {
    val closable = state is UpdateState.Available || state is UpdateState.Error
    Dialog(
        onDismissRequest = { if (closable) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = closable,
            dismissOnClickOutside = closable,
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            tonalElevation = 6.dp,
        ) {
            when (state) {
                is UpdateState.Available -> AvailableContent(state, onDismiss)
                is UpdateState.Downloading -> DownloadingContent(state)
                is UpdateState.ReadyToInstall -> ReadyContent(state)
                is UpdateState.Error -> ErrorContent(state, onDismiss)
                else -> { /* Idle, Checking, UpToDate — pas de rendu dialog */ }
            }
        }
    }
}

@Composable
private fun AvailableContent(
    state: UpdateState.Available,
    onDismiss: () -> Unit,
) {
    val release = state.release
    val currentVersion = remember { ScannerApp.instance.updateManager.currentVersion }

    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF4F46E5))
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                stringResource(R.string.update_available_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.update_available_body, currentVersion, release.version),
            fontSize = 13.sp,
            color = Color.Gray,
        )

        if (!release.notes.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.update_release_notes_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF4B5563),
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF3F4F6),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    release.notes.trim(),
                    fontSize = 12.sp,
                    color = Color(0xFF374151),
                    modifier = Modifier
                        .padding(10.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF3F4F6)),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.update_later_button),
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = { ScannerApp.instance.updateManager.download(release) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White)
                Spacer(Modifier.padding(start = 6.dp))
                Text(
                    stringResource(R.string.update_install_button),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DownloadingContent(state: UpdateState.Downloading) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF4F46E5))
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                stringResource(R.string.update_downloading),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "v${state.release.version}",
            fontSize = 13.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = state.progressPct / 100f,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = Color(0xFF4F46E5),
            trackColor = Color(0xFFE5E7EB),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${state.progressPct} %",
            fontSize = 12.sp,
            color = Color(0xFF4B5563),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadyContent(state: UpdateState.ReadyToInstall) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Default.InstallMobile, contentDescription = null, tint = Color(0xFF059669))
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                stringResource(R.string.update_ready_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.update_ready_body, state.release.version),
            fontSize = 13.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                ScannerApp.instance.updateManager.install(context, state.apkPath)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.InstallMobile, contentDescription = null, tint = Color.White)
            Spacer(Modifier.padding(start = 6.dp))
            Text(
                stringResource(R.string.update_ready_install),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ErrorContent(state: UpdateState.Error, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFDC2626))
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                stringResource(R.string.update_error_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            state.message,
            fontSize = 13.sp,
            color = Color(0xFF4B5563),
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF3F4F6)),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.common_close),
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = { ScannerApp.instance.updateManager.checkForUpdate(silent = false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.update_error_retry),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

