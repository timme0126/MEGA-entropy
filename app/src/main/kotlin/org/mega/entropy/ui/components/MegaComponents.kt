package org.mega.entropy.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mega.entropy.R
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropy.ui.theme.MegaNeutralGray
import org.mega.entropy.ui.theme.MegaSuccess


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
 * of seconds, not a toast or snackbar that could linger in a screenshot.
 *
 * Uses the real android.content.ClipboardManager (via getSystemService),
 * not Compose's androidx.compose.ui.platform.ClipboardManager wrapper
 * returned by LocalClipboardManager — the Compose type only exposes a bare
 * setText(AnnotatedString) with no way to attach ClipDescription extras,
 * and EXTRA_IS_SENSITIVE below needs exactly that. */
@Composable
fun MegaCopyIconButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    getTextToCopy: () -> String,
) {
    val context = LocalContext.current
    val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var copied by remember { mutableStateOf(false) }
    // What we last put on the clipboard, so the auto-clear effect below can
    // confirm the clipboard still holds exactly that before wiping it —
    // never clobber something else the user copied in the meantime.
    var lastCopiedText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    // Best-effort auto-clear ~60s after a copy, keyed on the copied value so
    // a second copy restarts the timer against the NEW value rather than
    // firing early against a stale one. Never crashes the app: a clipboard
    // read/write can fail for reasons outside MEGA's control (e.g. some
    // OEMs restrict background clipboard access), and this is a courtesy
    // cleanup, not a security boundary the rest of the app depends on.
    LaunchedEffect(lastCopiedText) {
        val textToClear = lastCopiedText ?: return@LaunchedEffect
        delay(60_000)
        try {
            val currentClipText = systemClipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (currentClipText == textToClear) {
                systemClipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Best-effort only — never crash the app over a clipboard clear.
        }
    }

    IconButton(
        onClick = {
            val textToCopy = getTextToCopy()
            val clipData = ClipData.newPlainText("MEGA", textToCopy)
            // API 33+: mark the clip sensitive so the platform can suppress
            // clipboard-content previews/suggestions elsewhere on the device
            // — every value this button ever copies (xpubs, descriptors,
            // WIF keys, and, when allowSeedCopy is explicitly enabled, seed
            // words) is exactly the kind of material that shouldn't linger
            // visibly in clipboard history/suggestion UIs.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clipData.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            systemClipboard.setPrimaryClip(clipData)
            lastCopiedText = textToCopy
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

/**
 * Read-only display of a passphrase decided on an earlier screen — a
 * green check or red X shows at a glance whether one was used at all,
 * and (only when one was) a reveal toggle shows the actual value without
 * ever offering a text field to re-type or edit it. Used anywhere a
 * derivation reuses a passphrase the user already committed to elsewhere
 * (BIP85 child derivation, standalone wallet-key derivation from Advanced
 * Mode) — re-entering the same decision on a second screen invites it to
 * silently drift from what was actually typed upstream.
 */
@Composable
fun MegaPassphraseCard(passphrase: String) {
    var revealed by remember(passphrase) { mutableStateOf(false) }
    val used = passphrase.isNotEmpty()
    MegaCard(title = "Passphrase") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (used) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = if (used) "A passphrase was used" else "No passphrase was used",
                tint = if (used) MegaSuccess else MegaError,
            )
            Text(
                text = if (used) "A passphrase was used." else "No passphrase was used.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (used) {
            MegaMonoText(if (revealed) passphrase else "•".repeat(passphrase.length))
            Text(
                text = if (revealed) "Hide passphrase" else "Show passphrase",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { revealed = !revealed },
            )
        }
    }
}

/**
 * The label dialog opened by a save-icon action (Advanced Mode hub, BIP85
 * child mnemonic, dice-flow Save Session, renaming an existing saved
 * session via [initialLabel]) — every saved session must have a label, so
 * it can be told apart from every other one later; Save stays disabled
 * until something is actually typed.
 */
@Composable
fun MegaLabelSessionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialLabel: String = "",
    title: String = "Label This Session",
    helperText: String = "A label is required so this session can be told apart from others later.",
) {
    var label by remember { mutableStateOf(initialLabel) }
    val trimmed = label.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    placeholder = { Text("e.g. Cold storage") },
                    isError = label.isNotEmpty() && trimmed.isEmpty(),
                )
                Text(
                    helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Transient "Saved as ..." banner shown after a save-icon action (Advanced
 * Mode hub, BIP85 child mnemonic) — auto-dismisses itself after a few
 * seconds via [onDismissed], which the caller uses to clear whatever
 * state is holding [label].
 */
@Composable
fun MegaSavedConfirmationCard(label: String, onDismissed: () -> Unit) {
    LaunchedEffect(label) {
        delay(3000)
        onDismissed()
    }
    MegaCard {
        Text(
            text = "Saved as \"$label\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun MegaSectionSpacer() = Box(modifier = Modifier.padding(top = 8.dp))

val MegaScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
