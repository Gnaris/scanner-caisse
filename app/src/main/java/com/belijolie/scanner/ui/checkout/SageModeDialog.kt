package com.belijolie.scanner.ui.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.R
import com.belijolie.scanner.export.SageInvoiceRef
import com.belijolie.scanner.export.SageMode

/**
 * Choix de l'action Sage 50 : nouvelle facture, ou ajouter / remplacer sur
 * une facture PROVISOIRE existante (liste lue dans Sage via Scanner Listener
 * → SageBridge), avec confirmation avant toute modification.
 */
@Composable
fun SageModeDialog(
    invoices: CheckoutViewModel.SageInvoices,
    onLoadInvoices: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (SageMode, String?) -> Unit,
) {
    var mode by remember { mutableStateOf<SageMode?>(null) }
    var picked by remember { mutableStateOf<SageInvoiceRef?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.sage_mode_title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
                Spacer(Modifier.height(14.dp))

                val m = mode
                val p = picked
                when {
                    // 1. Choix de l'action
                    m == null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeCard(Icons.Default.NoteAdd, Color(0xFF3B82F6), stringResource(R.string.sage_mode_create), stringResource(R.string.sage_mode_create_sub)) {
                            onConfirm(SageMode.CREER, null)
                        }
                        ModeCard(Icons.Default.AddCircle, Color(0xFF16A34A), stringResource(R.string.sage_mode_add), stringResource(R.string.sage_mode_add_sub)) {
                            mode = SageMode.AJOUTER; onLoadInvoices()
                        }
                        ModeCard(Icons.Default.SwapHoriz, Color(0xFFDC2626), stringResource(R.string.sage_mode_replace), stringResource(R.string.sage_mode_replace_sub)) {
                            mode = SageMode.REMPLACER; onLoadInvoices()
                        }
                    }
                    // 2. Choix de la facture provisoire
                    p == null -> Column {
                        Text(stringResource(R.string.sage_pick_invoice), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4B5563))
                        Spacer(Modifier.height(8.dp))
                        when (invoices) {
                            CheckoutViewModel.SageInvoices.Loading -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 16.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.sage_loading), fontSize = 13.sp, color = Color.Gray)
                            }
                            is CheckoutViewModel.SageInvoices.Error -> Column {
                                Text(invoices.message, fontSize = 13.sp, color = Color(0xFFB91C1C))
                                TextButton(onClick = onLoadInvoices) { Text(stringResource(R.string.sage_retry)) }
                            }
                            is CheckoutViewModel.SageInvoices.Loaded ->
                                if (invoices.list.isEmpty()) {
                                    Text(stringResource(R.string.sage_no_invoice), fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp))
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.heightIn(max = 320.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items(invoices.list, key = { it.numero }) { inv ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFFF3F4F6), RoundedCornerShape(10.dp))
                                                    .clickable { picked = inv }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(inv.numero, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
                                                    Text("${inv.nomClient} · ${inv.date}", fontSize = 12.sp, color = Color.Gray)
                                                }
                                                Text(formatEuro(inv.totalTtcCents.toInt()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                        }
                    }
                    // 3. Confirmation
                    else -> {
                        val text = if (m == SageMode.REMPLACER)
                            stringResource(R.string.sage_confirm_replace, p.numero, p.nomClient)
                        else
                            stringResource(R.string.sage_confirm_add, p.numero, p.nomClient)
                        Text(text, fontSize = 14.sp, color = if (m == SageMode.REMPLACER) Color(0xFFB91C1C) else Color(0xFF111111))
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onConfirm(m, p.numero) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (m == SageMode.REMPLACER) Color(0xFFDC2626) else Color(0xFF16A34A)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text(stringResource(R.string.common_confirm), color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (mode != null) {
                        TextButton(onClick = { if (picked != null) picked = null else mode = null }) {
                            Text(stringResource(R.string.common_back), color = Color.Gray)
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Color.Gray) }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(icon: ImageVector, tint: Color, label: String, sub: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(tint, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
            Text(sub, fontSize = 12.sp, color = Color.Gray)
        }
    }
}
