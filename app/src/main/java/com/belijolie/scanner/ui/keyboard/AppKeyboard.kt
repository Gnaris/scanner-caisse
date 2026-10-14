package com.belijolie.scanner.ui.keyboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import com.belijolie.scanner.scanner.ScanEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
            value = value,
            label = label,
            mode = mode,
            onChange = onValueChange,
            onDismiss = { sheetOpen = false }
        )
    }
}

@Composable
private fun KeyboardDialog(
    value: String,
    label: String,
    mode: KeyboardMode,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // The text itself lives in the parent (via `value`), so key presses update
    // the parent state immediately — no need to tap "Valider" to flush. We keep
    // selection locally and clamp it against the current value (which may have
    // been reshaped by the parent, e.g. digit-only filters).
    var selection by remember { mutableStateOf(TextRange(value.length)) }
    val clampedSel = TextRange(
        selection.start.coerceIn(0, value.length),
        selection.end.coerceIn(0, value.length)
    )
    val tfv = TextFieldValue(value, clampedSel)

    var currentMode by remember { mutableStateOf(mode) }
    val focusRequester = remember { FocusRequester() }
    val localView = LocalView.current
    val clipboard = LocalClipboardManager.current

    // Capture le dernier layout du BasicTextField pour pouvoir dessiner nous-même
    // un curseur à la bonne position (readOnly=true cache le curseur natif —
    // bug/limitation Compose, voir issuetracker 197402915).
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Garde-fou : on force quand même SOFT_INPUT_STATE_ALWAYS_HIDDEN sur la
    // fenêtre du Dialog. Mais la vraie barrière anti-IME, c'est `readOnly=true`
    // sur le BasicTextField plus bas — ce flag seul ne suffit pas quand le
    // KeyboardDialog est empilé par-dessus un AlertDialog (le clavier Android
    // réapparaissait au focus). readOnly=true coupe l'IME sans impact : nos
    // touches custom modifient `value` directement via onChange.
    LaunchedEffect(Unit) {
        val dialogWindow = (localView.parent as? DialogWindowProvider)?.window
        dialogWindow?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
    }

    val insert: (String) -> Unit = insert@{ s ->
        if (s.isEmpty()) return@insert
        val start = minOf(clampedSel.start, clampedSel.end)
        val end = maxOf(clampedSel.start, clampedSel.end)
        val newText = value.substring(0, start) + s + value.substring(end)
        selection = TextRange(start + s.length)
        onChange(newText)
    }
    val backspace: () -> Unit = backspace@{
        val start = minOf(clampedSel.start, clampedSel.end)
        val end = maxOf(clampedSel.start, clampedSel.end)
        if (start != end) {
            val newText = value.substring(0, start) + value.substring(end)
            selection = TextRange(start)
            onChange(newText)
            return@backspace
        }
        if (start == 0) return@backspace
        val newText = value.substring(0, start - 1) + value.substring(start)
        selection = TextRange(start - 1)
        onChange(newText)
    }

    LaunchedEffect(Unit) {
        // Scanner events overwrite the full value (we don't want partial scans
        // interleaved with manual input).
        ScanEventBus.events.collect { scanned ->
            selection = TextRange(scanned.length)
            onChange(scanned)
        }
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
                }
                .background(Color.Black.copy(alpha = 0.45f))
                // detectTapGestures ne vole PAS le focus du BasicTextField
                // (contrairement à clickable) → la sélection par long-press
                // reste intacte, et un tap simple hors du panneau ferme bien.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF4F5F7))
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    // Absorbe les taps sur le panneau pour qu'ils n'atteignent
                    // pas le Box parent (qui dismiss). Pas de clickable → pas
                    // de focus volé au TextField.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {})
                    }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color(0xFF6B7280),
                        modifier = Modifier.weight(1f)
                    )
                    // Bouton Coller : lit le presse-papiers et insère à la
                    // position du curseur. Le menu natif Android n'offre pas
                    // "Coller" en readOnly=true, donc on expose l'action ici.
                    IconButton(
                        onClick = {
                            val pasted = clipboard.getText()?.text ?: return@IconButton
                            if (pasted.isEmpty()) return@IconButton
                            val start = minOf(clampedSel.start, clampedSel.end)
                            val end = maxOf(clampedSel.start, clampedSel.end)
                            val newText = value.substring(0, start) + pasted + value.substring(end)
                            selection = TextRange(start + pasted.length)
                            onChange(newText)
                        },
                        modifier = Modifier.size(36.dp).focusProperties { canFocus = false }
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = "Coller",
                            tint = Color(0xFF4F46E5)
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Fermer la saisie",
                            tint = Color(0xFF6B7280)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        // Double-tap sur le champ = tout sélectionner (en plus
                        // du long-press natif qui sélectionne le mot). Pratique
                        // pour effacer d'un coup une IP / un barcode / un prix.
                        // detectTapGestures ne consume PAS les simples taps en
                        // Compose 1.5+ → le BasicTextField continue à les
                        // recevoir pour positionner le curseur.
                        .pointerInput(value) {
                            detectTapGestures(
                                onDoubleTap = {
                                    selection = TextRange(0, value.length)
                                }
                            )
                        }
                ) {
                    BasicTextField(
                        value = tfv,
                        onValueChange = { newTfv ->
                            selection = newTfv.selection
                            if (newTfv.text != value) onChange(newTfv.text)
                        },
                        onTextLayout = { textLayout = it },
                        // readOnly = true : garantit que le clavier Android ne
                        // s'ouvre JAMAIS, même quand le KeyboardDialog est
                        // empilé par-dessus un AlertDialog (cas où
                        // SOFT_INPUT_STATE_ALWAYS_HIDDEN ne suffit pas). Les
                        // poignées de sélection et le menu natif "Tout
                        // sélectionner / Copier" restent actifs. Le curseur
                        // natif est caché en readOnly (bug Compose connu) —
                        // on en dessine un manuellement par-dessus (voir
                        // bloc Canvas qui suit).
                        readOnly = true,
                        textStyle = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111111)
                        ),
                        cursorBrush = SolidColor(Color(0xFF4F46E5)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )

                    // Curseur dessiné à la main : trait vertical qui clignote
                    // à la position actuelle. Masqué quand une sélection est
                    // active (highlight pris en charge nativement par le
                    // BasicTextField même en readOnly).
                    val layout = textLayout
                    if (layout != null && clampedSel.collapsed) {
                        val cursorRect = runCatching {
                            layout.getCursorRect(clampedSel.start.coerceIn(0, value.length))
                        }.getOrNull()
                        if (cursorRect != null) {
                            val infinite = rememberInfiniteTransition(label = "cursorBlink")
                            val alpha by infinite.animateFloat(
                                initialValue = 1f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = keyframes {
                                        durationMillis = 1000
                                        1f at 0 using LinearEasing
                                        1f at 500 using LinearEasing
                                        0f at 500 using LinearEasing
                                        0f at 1000 using LinearEasing
                                    }
                                ),
                                label = "cursorAlpha"
                            )
                            val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
                            // matchParentSize() : prend la taille du Box parent
                            // sans participer à la mesure. fillMaxSize() ferait
                            // exploser le Canvas à l'écran entier et pousserait
                            // le clavier hors de l'écran.
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawLine(
                                    color = Color(0xFF4F46E5).copy(alpha = alpha),
                                    start = Offset(cursorRect.left, cursorRect.top),
                                    end = Offset(cursorRect.left, cursorRect.bottom),
                                    strokeWidth = strokePx
                                )
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Spacer(Modifier.height(10.dp))

                val canSwitchToAzerty = mode == KeyboardMode.TEXT || mode == KeyboardMode.SYMBOLS
                when (currentMode) {
                    KeyboardMode.NUMBER -> NumericPad(
                        dotMode = DotMode.NONE,
                        onChar = { insert(it.toString()) },
                        onBackspace = backspace,
                        canAddDot = { false },
                        canSwitchToAzerty = canSwitchToAzerty,
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                    KeyboardMode.DECIMAL -> NumericPad(
                        dotMode = DotMode.SINGLE,
                        onChar = { insert(it.toString()) },
                        onBackspace = backspace,
                        canAddDot = { !value.contains('.') },
                        canSwitchToAzerty = canSwitchToAzerty,
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                    KeyboardMode.IP -> NumericPad(
                        dotMode = DotMode.MULTIPLE,
                        onChar = { insert(it.toString()) },
                        onBackspace = backspace,
                        canAddDot = { true },
                        canSwitchToAzerty = canSwitchToAzerty,
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                    KeyboardMode.TEXT -> AzertyPad(
                        onChar = { insert(it.toString()) },
                        onBackspace = backspace,
                        onSpace = { insert(" ") },
                        onSwitchToSymbols = { currentMode = KeyboardMode.SYMBOLS }
                    )
                    KeyboardMode.SYMBOLS -> SymbolsPad(
                        onChar = { insert(it.toString()) },
                        onBackspace = backspace,
                        onSpace = { insert(" ") },
                        onSwitchToAzerty = { currentMode = KeyboardMode.TEXT }
                    )
                }

                Spacer(Modifier.height(6.dp))
                // Bouton Entrée : la valeur est déjà sync en direct → juste
                // fermer le clavier. Même rôle qu'un "Return" sur mobile.
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp).focusProperties { canFocus = false }
                ) {
                    Text(
                        "Entrée ⏎",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
            BackspaceButton(modifier = Modifier.weight(1f), onDelete = onBackspace)
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
    // Minuscules par défaut (demandé) ; Shift reste disponible via la touche ⇧.
    var shift by remember { mutableStateOf(false) }
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
            BackspaceButton(modifier = Modifier.weight(1.5f), onDelete = onBackspace)
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
            BackspaceButton(modifier = Modifier.weight(1.5f), onDelete = onBackspace)
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
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Backspace with iPhone-style hold-to-repeat: single tap deletes once, holding
 * the key keeps deleting after a short grace period, then faster while held.
 *
 * We drive both single and repeat deletes from the pressed state so the Button's
 * own `onClick` can stay empty — one press = one `onDelete()` call, release
 * cancels the follow-up repeat.
 */
@Composable
private fun BackspaceButton(modifier: Modifier = Modifier, onDelete: () -> Unit) {
    // rememberUpdatedState pour que le LaunchedEffect en cours (démarré au
    // premier press) appelle toujours la DERNIÈRE version de `onDelete` —
    // sinon il garde en closure une version figée au moment du press et
    // continue à supprimer sur l'état obsolète : la boucle semble ne rien
    // faire côté UI.
    val latestOnDelete by rememberUpdatedState(onDelete)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        latestOnDelete()
        delay(380)
        while (isActive) {
            latestOnDelete()
            delay(50)
        }
    }

    Button(
        onClick = { /* handled via pressed state above */ },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = KeySpecialBg, contentColor = KeyText),
        contentPadding = PaddingValues(0.dp),
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
        contentPadding = PaddingValues(0.dp),
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
        contentPadding = PaddingValues(0.dp),
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
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(48.dp).focusProperties { canFocus = false }
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

