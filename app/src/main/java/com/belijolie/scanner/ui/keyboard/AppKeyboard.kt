package com.belijolie.scanner.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.belijolie.scanner.scanner.ScanEventBus

enum class KeyboardMode {
    /** Digits 0-9 only. For barcodes, port numbers. */
    NUMBER,
    /** Digits 0-9 + single '.' (for prices like 8.50). */
    DECIMAL,
    /** Digits 0-9 + unlimited '.' (for IP addresses). */
    IP,
    /** Full AZERTY letters + digits + common punctuation. */
    TEXT,
    /** Symbols pad: punctuation, accents, operators. */
    SYMBOLS
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    mode: KeyboardMode,
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {
    var sheetOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable { sheetOpen = true }
                .padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            if (value.isEmpty()) {
                Text(placeholder.ifEmpty { "—" }, color = Color.Gray)
            } else {
                Text(value, fontSize = 16.sp)
            }
        }
    }

    if (sheetOpen) {
        KeyboardDialog(
            initialValue = value,
            label = label,
            mode = mode,
            onDismiss = { sheetOpen = false },
            onValidate = {
                onValueChange(it)
                sheetOpen = false
            }
        )
    }
}

@Composable
private fun KeyboardDialog(
    initialValue: String,
    label: String,
    mode: KeyboardMode,
    onDismiss: () -> Unit,
    onValidate: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    var currentMode by remember { mutableStateOf(mode) }

    LaunchedEffect(Unit) {
        ScanEventBus.events.collect { scanned -> text = scanned }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF4F5F7))
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 8.dp)
            ) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        text.ifEmpty { " " },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF111111)
                    )
                }
                Spacer(Modifier.height(10.dp))

                val canSwitchToAzerty = mode == KeyboardMode.TEXT || mode == KeyboardMode.SYMBOLS
                when (currentMode) {
                    KeyboardMode.NUMBER -> NumericPad(
                        dotMode = DotMode.NONE,
                        onChar = { text += it },
                        onBackspace = { text = text.dropLast(1) },
                        canAddDot = { false },
                        canSwitchToAzerty = canSwitchToAzerty,
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                    KeyboardMode.DECIMAL -> NumericPad(
                        dotMode = DotMode.SINGLE,
                        onChar = { text += it },
                        onBackspace = { text = text.dropLast(1) },
                        canAddDot = { !text.contains('.') },
                        canSwitchToAzerty = canSwitchToAzerty,
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                    KeyboardMode.IP -> NumericPad(
                        dotMode = DotMode.MULTIPLE,
                        onChar = { text += it },
                        onBackspace = { text = text.dropLast(1) },
                        canAddDot = { true },
                        canSwitchToAzerty = canSwitchToAzerty,
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                    KeyboardMode.TEXT -> AzertyPad(
                        onChar = { text += it },
                        onBackspace = { text = text.dropLast(1) },
                        onSpace = { text += ' ' },
                        onSwitchToSymbols = { currentMode = KeyboardMode.SYMBOLS }
                    )
                    KeyboardMode.SYMBOLS -> SymbolsPad(
                        onChar = { text += it },
                        onBackspace = { text = text.dropLast(1) },
                        onSpace = { text += ' ' },
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onValidate(text) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.size(8.dp))
                    Text("Valider", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

private enum class DotMode { NONE, SINGLE, MULTIPLE }

@Composable
private fun NumericPad(
    dotMode: DotMode,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    canAddDot: () -> Boolean,
    canSwitchToAzerty: Boolean,
    onSwitchToAzerty: () -> Unit
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9')
    )
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    KeyButton(ch.toString(), modifier = Modifier.weight(1f), onClick = { onChar(ch) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            if (dotMode == DotMode.NONE) {
                Box(modifier = Modifier.weight(1f))
            } else {
                KeyButton(".", modifier = Modifier.weight(1f), enabled = canAddDot(), onClick = { onChar('.') })
            }
            KeyButton("0", modifier = Modifier.weight(1f), onClick = { onChar('0') })
            BackspaceButton(modifier = Modifier.weight(1f), onClick = onBackspace)
        }
        if (canSwitchToAzerty) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                SpecialButton("ABC", modifier = Modifier.weight(1f), onClick = onSwitchToAzerty)
            }
        }
    }
}

@Composable
private fun AzertyPad(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onSwitchToSymbols: () -> Unit
) {
    var shift by remember { mutableStateOf(true) }
    val letterRows = listOf("AZERTYUIOP", "QSDFGHJKLM", "WXCVBN")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Digits row (fixed, always visible)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            "1234567890".forEach { ch ->
                KeyButton(ch.toString(), modifier = Modifier.weight(1f), onClick = { onChar(ch) })
            }
        }
        // Letters rows 1 and 2
        letterRows.take(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    val display = if (shift) ch else ch.lowercaseChar()
                    KeyButton(display.toString(), modifier = Modifier.weight(1f), onClick = { onChar(display) })
                }
            }
        }
        // Letters row 3 with shift + backspace
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            ShiftButton(on = shift, modifier = Modifier.weight(1.5f), onClick = { shift = !shift })
            letterRows[2].forEach { ch ->
                val display = if (shift) ch else ch.lowercaseChar()
                KeyButton(display.toString(), modifier = Modifier.weight(1f), onClick = { onChar(display) })
            }
            // Few extra punctuation keys to fill the row
            KeyButton(",", modifier = Modifier.weight(1f), onClick = { onChar(',') })
            KeyButton(".", modifier = Modifier.weight(1f), onClick = { onChar('.') })
            BackspaceButton(modifier = Modifier.weight(1.5f), onClick = onBackspace)
        }
        // Bottom controls
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            SpecialButton("?123", modifier = Modifier.weight(1.5f), onClick = onSwitchToSymbols)
            KeyButton("'", modifier = Modifier.weight(1f), onClick = { onChar('\'') })
            SpaceButton(modifier = Modifier.weight(4f), onClick = onSpace)
            KeyButton("-", modifier = Modifier.weight(1f), onClick = { onChar('-') })
            KeyButton("?", modifier = Modifier.weight(1f), onClick = { onChar('?') })
            KeyButton("!", modifier = Modifier.weight(1f), onClick = { onChar('!') })
        }
    }
}

@Composable
private fun SymbolsPad(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onSwitchToAzerty: () -> Unit
) {
    var page by remember { mutableStateOf(0) }

    // Page 0: common punctuation + digits
    val page0Rows = listOf(
        "1234567890",
        "@#\$€&?!()'",
        "-_+=*/:;,.",
        "\"[]{}<>|\\~"
    )
    // Page 1: accents + less-common
    val page1Rows = listOf(
        "éèàçùâêîôû",
        "ÉÈÀÇÙÂÊÎÔÛ",
        "§£µ°²¨^`%",
        "±×÷≠«»…€¢"
    )
    val rows = if (page == 0) page0Rows else page1Rows

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    KeyButton(ch.toString(), modifier = Modifier.weight(1f), onClick = { onChar(ch) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            SpecialButton("ABC", modifier = Modifier.weight(1.3f), onClick = onSwitchToAzerty)
            SpecialButton(
                if (page == 0) "àéê" else "123",
                modifier = Modifier.weight(1.3f),
                onClick = { page = 1 - page }
            )
            SpaceButton(modifier = Modifier.weight(4f), onClick = onSpace)
            BackspaceButton(modifier = Modifier.weight(1.5f), onClick = onBackspace)
        }
    }
}

private val KeyBg = Color(0xFF2A2F3A)
private val KeySpecialBg = Color(0xFF4F46E5)
private val KeyText = Color(0xFFFFFFFF)
private val KeyDisabled = Color(0xFF55606F)

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = KeyBg,
            contentColor = KeyText,
            disabledContainerColor = KeyDisabled,
            disabledContentColor = Color.White.copy(alpha = 0.5f)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BackspaceButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = KeySpecialBg, contentColor = KeyText),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text("⌫", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpaceButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = KeyBg, contentColor = KeyText),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text("espace", fontSize = 13.sp)
    }
}

@Composable
private fun ShiftButton(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) KeySpecialBg else KeyBg,
            contentColor = KeyText
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text("⇧", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpecialButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = KeySpecialBg, contentColor = KeyText),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
