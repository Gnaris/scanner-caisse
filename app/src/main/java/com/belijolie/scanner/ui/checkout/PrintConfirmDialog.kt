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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.belijolie.scanner.printer.TicketLang

/**
 * Dialog de confirmation d'impression + sélecteur de langue du ticket.
 * Par défaut : français. Le choix est transmis à [onConfirm].
 */
@Composable
fun PrintConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (TicketLang) -> Unit
) {
    var lang by remember { mutableStateOf(TicketLang.FR) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    stringResource(R.string.print_confirm_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.print_confirm_body),
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    stringResource(R.string.print_lang_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF4B5563)
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LangOption(
                        label = stringResource(R.string.print_lang_fr),
                        sub = "FR",
                        selected = lang == TicketLang.FR,
                        onClick = { lang = TicketLang.FR },
                        modifier = Modifier.weight(1f)
                    )
                    LangOption(
                        label = stringResource(R.string.print_lang_en),
                        sub = "EN",
                        selected = lang == TicketLang.EN,
                        onClick = { lang = TicketLang.EN },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF3F4F6)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = Color(0xFF374151),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = { onConfirm(lang) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(stringResource(R.string.common_print), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LangOption(
    label: String,
    sub: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) Color(0xFF4F46E5) else Color(0xFFF3F4F6)
    val fg = if (selected) Color.White else Color(0xFF111111)
    val subFg = if (selected) Color(0xFFE0E7FF) else Color.Gray
    Box(
        modifier = modifier
            .height(60.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = subFg, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}
