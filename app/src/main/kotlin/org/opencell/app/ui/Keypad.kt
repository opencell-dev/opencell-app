package org.opencell.app.ui

import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.opencell.app.ui.theme.CallGreen
import org.opencell.core.phone.DialHint
import org.opencell.core.phone.DialPad
import org.opencell.core.phone.ServiceNumbers
import org.opencell.core.protocol.PhoneNumber

/** What the keypad does; the screen wires these to [MainViewModel]. */
class KeypadActions(
    val onKey: (Char) -> Unit,
    val onPlus: () -> Unit,
    val onBackspace: () -> Unit,
    val onClear: () -> Unit,
    val onPaste: (String) -> Unit,
    val onCall: () -> Unit,
    val onTestNumber: (String) -> Unit,
)

/**
 * Sizes the dial screen works with, from the current font size (large font
 * makes the text rows taller). [columnMin] and [sideMin] are the least height
 * each layout needs with 48 dp keys; the Phone tab keeps that much for the
 * keypad and gives the rest to the cards above it ([KeypadLayout]).
 */
@Immutable
class KeypadMetrics(
    val number: Dp,
    val hint: Dp,
) {
    fun gridMin(side: Boolean): Dp = MIN_KEY * 4 + rowGap(side) * 3
    fun callRow(side: Boolean): Dp = callSize(side) + 8.dp

    /** The single column: hint, number, keys, then Call (with Test numbers and Delete beside it). */
    val columnMin: Dp get() = number + hint + gridMin(false) + callRow(false) + PAD * 2

    /** Side by side: hint, number and Call on the left, the keys on the right. */
    val sideMin: Dp get() = max(number + hint + callRow(true), gridMin(true)) + PAD * 2

    companion object {
        /** The least width for side by side: a left column wide enough for the longest number, and 48 dp keys. */
        val SIDE_MIN_WIDTH = 200.dp + MIN_KEY * 3 + 8.dp * 2 + 16.dp + 32.dp
    }
}

@Composable
fun keypadMetrics(): KeypadMetrics {
    val t = MaterialTheme.typography
    return with(LocalDensity.current) {
        KeypadMetrics(
            number = max(56.dp, t.headlineSmall.lineHeight.toDp() + 8.dp),
            hint = t.bodyMedium.lineHeight.toDp() * 2,
        )
    }
}

/** How the dial screen is laid out: one column, or (a window too short for it) the number beside the keys. */
enum class KeypadLayout {
    COLUMN, SIDE;

    companion object {
        /** The column if it fits in [height]; side by side if not and [width] allows; else the column anyway. */
        fun choose(width: Dp, height: Dp, m: KeypadMetrics): KeypadLayout =
            if (height < m.columnMin && width >= KeypadMetrics.SIDE_MIN_WIDTH) SIDE else COLUMN
    }
}

/**
 * The dial screen (dial-and-recents spec §3): the line saying what Call will
 * dial, the number as typed, grouped, right above the 3 x 4 keypad, and the
 * Call row (Call, enabled only for a number [DialPad.hint] accepts while
 * [canDial]; "Test numbers" to its left; Delete to its right). Stateless:
 * [input] and [hint] come from the caller.
 *
 * The keypad is the screen: its three columns span [WIDTH_SHARE] of the width
 * and its rows take the height left after the text rows (fixed, from the font
 * size), so keys are as wide as the width allows and as tall as the height
 * does, up to square, never under 48 dp. Nothing scrolls. A window too short
 * for the column ([KeypadLayout.choose], or [layout] when the caller has
 * already chosen) puts the hint, number and Call beside the keys.
 */
@Composable
fun Keypad(
    input: String,
    hint: DialHint,
    canDial: Boolean,
    notice: String?,
    actions: KeypadActions,
    modifier: Modifier = Modifier,
    layout: KeypadLayout? = null,
) {
    val m = keypadMetrics()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = (layout ?: KeypadLayout.choose(maxWidth, maxHeight, m)) == KeypadLayout.SIDE
        if (side) {
            val gapX = colGap(true)
            val gapY = rowGap(true)
            val keyW = ((maxWidth / 2 - 24.dp) * WIDTH_SHARE - gapX * 2) / 3
            val keyH = min((maxHeight - PAD * 2 - gapY * 3) / 4, keyW)
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = PAD),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    HintLine(hint, canDial, notice, m.hint)
                    NumberDisplay(input, m.number, actions)
                    CallRow(input, hint, canDial, side = true, actions)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    KeyGrid(keyW.coerceAtLeast(MIN_KEY), keyH.coerceIn(MIN_KEY, MAX_KEY), gapX, gapY, actions)
                }
            }
        } else {
            val gapX = colGap(false)
            val gapY = rowGap(false)
            val fixed = m.number + m.hint + m.callRow(false) + PAD * 2
            val keyW = ((maxWidth - 16.dp) * WIDTH_SHARE - gapX * 2) / 3
            val keyH = min((maxHeight - fixed - gapY * 3) / 4, keyW)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = PAD),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Spare height (a key can't grow past square) goes above the text: the number stays
                // right on top of the keys, and the keys and Call stay at the bottom, under the thumb.
                Spacer(Modifier.weight(1f))
                HintLine(hint, canDial, notice, m.hint)
                NumberDisplay(input, m.number, actions)
                KeyGrid(keyW.coerceAtLeast(MIN_KEY), keyH.coerceIn(MIN_KEY, MAX_KEY), gapX, gapY, actions)
                CallRow(input, hint, canDial, side = false, actions)
            }
        }
    }
}

private val MIN_KEY = 48.dp
private val MAX_KEY = 120.dp
private val PAD = 8.dp

/** The share of the width the three columns of keys span. */
private const val WIDTH_SHARE = 0.9f

private fun colGap(side: Boolean): Dp = if (side) 8.dp else 14.dp
private fun rowGap(side: Boolean): Dp = if (side) 6.dp else 10.dp

/** Call's diameter: 80 dp in the column, 56 dp beside the keys (never under the 48 dp minimum). */
private fun callSize(side: Boolean): Dp = if (side) 56.dp else 80.dp

/** Delete's square: 56 dp, 48 dp beside the keys. */
private fun deleteSize(side: Boolean): Dp = if (side) 48.dp else 56.dp

/**
 * The number, grouped as typed ([DialPad.format]) and shrinking to fit its
 * row; a long press offers Paste and Copy. It has no tap action (TalkBack
 * offers only the long press), and it is a polite live region, so each change
 * is read out.
 */
@Composable
private fun NumberDisplay(input: String, height: Dp, actions: KeypadActions) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    menu = true
                })
            }
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                onLongClick(label = "Paste or copy the number") { menu = true; true }
            }
            .testTag(NUMBER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (input.isEmpty()) {
            Text(
                "Enter a number",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            BasicText(
                DialPad.format(input),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 40.sp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Paste") },
                onClick = {
                    menu = false
                    scope.launch {
                        val clip = clipboard.getClipEntry()?.clipData
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text != null) actions.onPaste(text)
                    }
                },
            )
            if (input.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        menu = false
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Number", input))) }
                    },
                )
            }
        }
    }
}

/**
 * What Call will dial, why it can't, or a refusal ([notice]) from the last
 * try, in a row of fixed [height] (two lines) so the keys don't jump: a
 * longer message shrinks to fit.
 */
@Composable
private fun HintLine(hint: DialHint, canDial: Boolean, notice: String?, height: Dp) {
    val (text, error) = when {
        notice != null -> notice to true
        !canDial -> "Calls need the terminal registered." to false
        else -> hint.text to hint.error
    }
    val style = MaterialTheme.typography.bodyMedium
    BasicText(
        text,
        modifier = Modifier.fillMaxWidth().height(height).padding(horizontal = 8.dp).wrapContentHeight(),
        style = style.copy(
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        ),
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize),
    )
}

/** The core test services ([ServiceNumbers]), a small button beside Call; choosing one calls it. */
@Composable
private fun TestNumbers(canDial: Boolean, onTestNumber: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        TextButton(onClick = { open = true }, enabled = canDial, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("Test numbers", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ServiceNumbers.ALL.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.label)
                            Text(PhoneNumber.display(s.number), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        open = false
                        onTestNumber(s.number)
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyGrid(width: Dp, height: Dp, gapX: Dp, gapY: Dp, actions: KeypadActions) {
    Column(verticalArrangement = Arrangement.spacedBy(gapY)) {
        DialPad.KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gapX)) {
                row.forEach { k ->
                    DialKey(k, width, height, onPress = { actions.onKey(k) }, onLongPress = if (k == '0') actions.onPlus else null)
                }
            }
        }
    }
}

/**
 * One key, [width] x [height] (a circle when square, rounded when wider): the
 * character, its letters under it, a haptic tick (the system's touch feedback
 * setting decides). TalkBack hears "5", "Star", "Pound"; 0 offers "Plus" as its
 * long-press action. The character is 34 sp on a full-size key and shrinks to
 * fit a small one or large font; the letters show when there is room.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(key: Char, width: Dp, height: Dp, onPress: () -> Unit, onLongPress: (() -> Unit)?) {
    val view = LocalView.current
    val press = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPress()
    }
    val long = onLongPress?.let {
        {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            it()
        }
    }
    val letters = DialPad.letters(key)
    val showLetters = height >= 64.dp && LocalDensity.current.fontScale <= 1.5f
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(width, height)
            .clip(shape)
            .combinedClickable(onClick = press, onLongClick = long)
            .clearAndSetSemantics {
                contentDescription = DialPad.spoken(key)
                role = Role.Button
                onClick { press(); true }
                if (long != null) onLongClick(label = "Plus") { long(); true }
            },
    ) {
        Column(
            Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                key.toString(),
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 34.sp),
            )
            if (showLetters && (letters.isNotEmpty() || key in "123456789")) {
                Text(
                    // 1 has no letters: a blank line keeps its digit level with the others.
                    letters.ifEmpty { " " },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Call in the middle; "Test numbers" to its left and Delete (long press:
 * clear) to its right, so nothing else takes a row of its own. Call and
 * Delete stay at or above the 48 dp touch minimum ([callSize], [deleteSize]);
 * Call stays green when off, only faded. Delete shows when there is something
 * to delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(input: String, hint: DialHint, canDial: Boolean, side: Boolean, actions: KeypadActions) {
    val view = LocalView.current
    val call = callSize(side)
    val delete = deleteSize(side)
    Row(
        Modifier.fillMaxWidth().height(call + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TestNumbers(canDial, actions.onTestNumber, Modifier.weight(1f))
        FilledIconButton(
            onClick = actions.onCall,
            enabled = canDial && hint.callable,
            modifier = Modifier.size(call),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CallGreen,
                contentColor = Color.White,
                disabledContainerColor = CallGreen.copy(alpha = 0.3f),
                disabledContentColor = Color.White.copy(alpha = 0.6f),
            ),
        ) {
            Icon(Icons.Filled.Call, contentDescription = "Call", modifier = Modifier.size(call * 0.45f))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (input.isNotEmpty()) {
                Box(
                    Modifier
                        .size(delete)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                actions.onBackspace()
                            },
                            onLongClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                actions.onClear()
                            },
                            onClickLabel = "Delete",
                            onLongClickLabel = "Clear the number",
                        )
                        .semantics { contentDescription = "Delete" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CallIcons.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The number display's test tag (UI tests read the grouped number from it). */
const val NUMBER_TAG = "dial-number"
