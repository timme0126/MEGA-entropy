package org.mega.entropy.ui.pin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.SecureRandom

/**
 * SECURITY NOTE (spec section 21): the shuffle below uses SecureRandom
 * purely to reorder which screen position shows which digit. This value
 * never leaves ui/pin, is never passed to :entropy-core (which cannot even
 * import android.* or java.security.SecureRandom — see its securityAudit
 * Gradle task), and has zero relationship to wallet entropy. It exists
 * only to make PIN entry harder to shoulder-surf, the same technique used
 * by several hardware-wallet-adjacent apps.
 */
private fun shuffledDigits(): List<Int> {
    val digits = (0..9).toMutableList()
    val random = SecureRandom()
    for (i in digits.indices.reversed()) {
        val j = random.nextInt(i + 1)
        val tmp = digits[i]
        digits[i] = digits[j]
        digits[j] = tmp
    }
    return digits
}

/** Standard phone dial-pad order: 1-9 then 0, the layout every user already
 * expects. Used for choosing a new PIN, where there's nothing to protect
 * against yet — see [ScrambledKeypad]'s `scrambled` parameter. */
private val orderedDigits: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

/**
 * A 3-column grid of digits. The first 9 digits fill 3 full rows; the 10th
 * digit occupies the center of a 4th row, flanked by [onClear] (left) and
 * [onDelete] (right) — the standard phone-dial-pad "0 between functions"
 * layout, so Enter never needs a 5th row below it. When [scrambled] is true
 * (the default — used to unlock/verify an existing PIN), digit positions
 * (including which digit lands in that flanked center slot) are reshuffled
 * every time this composable enters composition and again whenever the
 * caller changes [shuffleKey] (used for "reshuffle after an incorrect
 * attempt", per spec section 21) — Clear/Delete themselves are never part
 * of the shuffle, always in the same two positions. When [scrambled] is
 * false (used for choosing a new PIN), digits are always in standard
 * 1-9-then-0 dial-pad order, so the flanked digit is always 0.
 */
@Composable
fun ScrambledKeypad(
    shuffleKey: Any,
    onDigitTapped: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    scrambled: Boolean = true,
    deleteEnabled: Boolean = true,
    clearEnabled: Boolean = true,
) {
    val digits = if (scrambled) remember(shuffleKey) { shuffledDigits() } else orderedDigits
    val fullRows = digits.subList(0, 9).chunked(3)
    val lastDigit = digits[9]

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fullRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { digit ->
                    KeypadButton(digit = digit, onTapped = onDigitTapped, modifier = Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FunctionButton(
                label = "CLR",
                onTapped = onClear,
                enabled = clearEnabled,
                modifier = Modifier.weight(1f),
            )
            KeypadButton(digit = lastDigit, onTapped = onDigitTapped, modifier = Modifier.weight(1f))
            FunctionButton(
                label = "⌫",
                onTapped = onDelete,
                enabled = deleteEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeypadButton(digit: Int, onTapped: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = { onTapped(digit) },
        modifier = modifier.aspectRatio(1f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(text = digit.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Circular Clear/Delete button flanking the lone 10th digit — same size
 * and shape as [KeypadButton] but visually distinct (filled, no border) so
 * it doesn't look like a digit. */
@Composable
private fun FunctionButton(
    label: String,
    onTapped: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Surface(
        onClick = onTapped,
        enabled = enabled,
        modifier = modifier.aspectRatio(1f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = contentColor)
        }
    }
}

/** PIN progress dots per spec section 20: "PIN display should use dots
 * rather than digits." Never renders the actual entered digits. */
@Composable
fun PinDots(enteredCount: Int, maxLength: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(maxLength) { index ->
            val filled = index < enteredCount
            Surface(
                modifier = Modifier.size(16.dp),
                shape = CircleShape,
                color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}
