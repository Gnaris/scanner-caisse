package com.belijolie.scanner.ui.checkout

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.R
import com.belijolie.scanner.ui.keyboard.AppTextField
import com.belijolie.scanner.ui.keyboard.KeyboardMode

/**
 * Modal d'encaissement : affiche le total HT, laisse saisir le montant reçu,
 * et calcule en direct le reste à régler (ou le rendu si on dépasse).
 * Un bouton « Clôturer le canal » (avec confirmation) permet de supprimer
 * le canal définitivement sans faire d'encaissement.
 */
@Composable
fun CashPaymentSheet(
    totalHtCents: Int,
    channelName: String = "",
    onDismiss: () -> Unit,
    onCloseChannel: (() -> Unit)? = null,
) {
    var receivedText by remember { mutableStateOf("") }
    val receivedCents = parseAmountCents(receivedText)
    val diffCents = (receivedCents ?: 0) - totalHtCents
    var showCloseConfirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF4F5F7))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.cash_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                Spacer(Modifier.height(12.dp))

                AmountCard(
                    label = stringResource(R.string.cash_total_ht),
                    value = formatEuro(totalHtCents),
                    valueColor = Color.Black
                )
                Spacer(Modifier.height(10.dp))

                AppTextField(
                    value = receivedText,
                    onValueChange = { receivedText = it },
                    label = stringResource(R.string.cash_amount_received),
                    mode = KeyboardMode.DECIMAL
                )
                Spacer(Modifier.height(12.dp))

                val labelRemaining = stringResource(R.string.cash_remaining)
                val labelChange = stringResource(R.string.cash_change)
                val labelSettled = stringResource(R.string.cash_settled)
                val (resteLabel, resteValue, resteColor) = when {
                    receivedCents == null -> Triple(labelRemaining, formatEuro(totalHtCents), Color(0xFFB91C1C))
                    diffCents > 0 -> Triple(labelChange, formatEuro(diffCents), Color(0xFF15803D))
                    diffCents == 0 -> Triple(labelSettled, formatEuro(0), Color(0xFF15803D))
                    else -> Triple(labelRemaining, formatEuro(-diffCents), Color(0xFFB91C1C))
                }
                AmountCard(
                    label = resteLabel,
                    value = resteValue,
                    valueColor = resteColor,
                    big = true
                )
                if (onCloseChannel != null) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { showCloseConfirm = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.cash_close_channel),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showCloseConfirm && onCloseChannel != null) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text(stringResource(R.string.cash_close_confirm_title)) },
            text = { Text(stringResource(R.string.cash_close_confirm_body, channelName)) },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    onCloseChannel()
                }) {
                    Text(
                        stringResource(R.string.common_close),
                        color = Color(0xFFB91C1C),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun AmountCard(
    label: String,
    value: String,
    valueColor: Color,
    big: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (big) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = if (big) 16.sp else 14.sp,
                fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium,
                color = Color(0xFF374151)
            )
            Text(
                value,
                fontSize = if (big) 24.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
    }
}

private fun parseAmountCents(text: String): Int? {
    if (text.isBlank()) return null
    val normalized = text.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 100).toInt()
}
