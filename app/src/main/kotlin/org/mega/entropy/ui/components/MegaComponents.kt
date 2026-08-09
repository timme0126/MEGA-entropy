package org.mega.entropy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mega.entropy.R
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropy.ui.theme.MegaNeutralGray


/**
 * Brand wordmark treatment follows the active theme: dark mode keeps the
 * original transparent wordmark, while light mode supplies the black backing
 * the original artwork expects.
 */
@Composable
fun MegaLogo(
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSystemInDarkTheme()) Color.Transparent else Color.Black

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1672f / 941f)
            .background(backgroundColor)
            .padding(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.mega_wordmark),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Primary CTA button — large touch target, full width, per spec section 44. */
@Composable
fun MegaPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun MegaSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Deliberately neutral filled button (fixed gray, not theme-adaptive) —
 * used for lower-emphasis actions like "View" / "Label" that shouldn't
 * compete visually with the orange primary actions on the same card. */
@Composable
fun MegaNeutralButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MegaNeutralGray,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Destructive filled button (fixed red, not theme-adaptive) — reserved for
 * irreversible bulk actions like "Secure Delete All", distinct from the
 * orange used for a single, more contained delete. */
@Composable
fun MegaDestructiveButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MegaError,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** A card section with an optional title, used throughout the explanation
 * and calculation screens so every "show your work" block looks the same.
 * [leadingAction] and [trailingAction] put a small icon button (copy,
 * lock/unlock, ...) in the card's header row instead of a separate row —
 * the small action sits directly in the top-left/top-right corner without
 * stealing extra vertical space or nesting another card inside this one. */
@Composable
fun MegaCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || leadingAction != null || trailingAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    leadingAction?.invoke()
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                    trailingAction?.invoke()
                }
            }
            content()
        }
    }
}

/** Small icon-only copy action for sensitive word lists (seed words, BIP85
 * child words) — used instead of a full-width "Copy ..." button so the
 * action reads as low-key and deliberate rather than prominent. Feedback on
 * a successful copy is the icon itself swapping to a checkmark for a couple
 * of seconds, not a toast or snackbar that could linger in a screenshot. */
@Composable
fun MegaCopyIconButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    getTextToCopy: () -> String,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    IconButton(
        onClick = {
            clipboardManager.setText(AnnotatedString(getTextToCopy()))
            copied = true
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = if (copied) "Copied" else contentDescription,
            tint = if (copied) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** Small icon-only lock/unlock toggle for the dice-roll edit affordance —
 * per-screen UI state (not persisted), defaulting to unlocked so existing
 * edit behavior is unchanged unless the user deliberately locks it. */
@Composable
fun MegaLockIconButton(
    locked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (locked) "Rolls locked — tap to unlock editing" else "Rolls unlocked — tap to lock editing",
        )
    }
}

/** Monospace block for anything numeric/calculated — dice digits, X, hex,
 * bit groups — so a reviewer's eye can line up digits column by column.
 * See spec sections 6 and 12 ("Show the math", hex representation). */
@Composable
fun MegaMonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        color = color,
    )
}

@Composable
fun MegaSectionSpacer() = Box(modifier = Modifier.padding(top = 8.dp))

val MegaScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
