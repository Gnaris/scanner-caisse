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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.R
import com.belijolie.scanner.export.ExportFormat

/**
 * Picker de format d'export. Trois cartes côte à côte (Excel vert, JSON
 * orange, Sage 50 bleu), chaque carte = icône + libellé + sous-texte.
 * Design pensé pour le PDA (petit écran) : cartes carrées égales, texte
 * compact, zone cliquable large.
 */
@Composable
fun ExportFormatPickerDialog(
    onDismiss: () -> Unit,
    onPick: (ExportFormat) -> Unit
) {
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
                    stringResource(R.string.export_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.export_subtitle),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ExportCard(
                        label = stringResource(R.string.export_excel),
                        subLabel = stringResource(R.string.export_excel_sub),
                        icon = Icons.Default.TableChart,
                        tint = Color(0xFF10B981), // emerald
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(ExportFormat.CSV) }
                    )
                    ExportCard(
                        label = stringResource(R.string.export_json),
                        subLabel = stringResource(R.string.export_json_sub),
                        icon = Icons.Default.Code,
                        tint = Color(0xFFF59E0B), // amber
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(ExportFormat.JSON) }
                    )
                    ExportCard(
                        label = stringResource(R.string.export_sage),
                        subLabel = stringResource(R.string.export_sage_sub),
                        icon = Icons.Default.Receipt,
                        tint = Color(0xFF3B82F6), // blue
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(ExportFormat.SAGE) }
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel), color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    label: String,
    subLabel: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = tint.copy(alpha = 0.12f)
    Surface(
        modifier = modifier
            .height(118.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tint, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
                textAlign = TextAlign.Center
            )
            Text(
                subLabel,
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}
