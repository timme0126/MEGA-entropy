package org.mega.entropy.ui.selftest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.mega.entropycore.CryptoSelfTest

/** Names that mean "a resource or bootstrap problem", not "this
 * device computed a wrong value" — wording must not accuse the
 * hardware of corrupt math over them. */
private val RESOURCE_FAULT_NAMES = setOf(
    CryptoSelfTest.MEMORY_EXHAUSTED,
    CryptoSelfTest.STARTUP_FAILURE,
)

/**
 * Composes the full human-language explanation of a crypto self-test
 * failure. Pure builder (no framework types) so the exact wording —
 * the part a user must actually understand when the app goes dark on a
 * signer device — is unit-testable, and so nothing about the message is
 * attacker-influenceable: every token comes from the fixed literals
 * below plus the fixed check-name constants from [CryptoSelfTest].
 */
fun buildSelfTestFailureMessage(failedCheckNames: List<String>): String = buildString {
    appendLine("MEGA's built-in safety check failed on this device.")
    appendLine()
    if (failedCheckNames.isNotEmpty() && failedCheckNames.all { it in RESOURCE_FAULT_NAMES }) {
        appendLine(
            "MEGA could not finish its own safety checks because this Android system ran out " +
                "of resources or MEGA failed to start correctly. This has NOT been proven to be " +
                "a wrong calculation — it is a resource or startup problem. Nothing has been " +
                "changed on this device.",
        )
        append("\n")
        append(
            "Close other apps to free memory and try again. If MEGA keeps failing to start on " +
                "every device you try, do not use it for signing.",
        )
        return@buildString
    }
    appendLine(
        "Before showing any key material, MEGA recomputes a set of published Bitcoin test " +
            "vectors and compares every result byte for byte. This time, " +
            "${failedCheckNames.size} check${if (failedCheckNames.size == 1) "" else "s"} did not match:",
    )
    failedCheckNames.forEach { name ->
        appendLine("• $name")
    }
    appendLine()
    appendLine(
        "That means either MEGA's files on this device are damaged or were replaced, or this " +
            "device itself is producing wrong calculations. Either way, addresses or signatures " +
            "computed here cannot be trusted, and funds sent to such an address could be lost " +
            "permanently. Nothing has been changed on this device.",
    )
    append("\n")
    append(
        "Do not create wallets, show recovery phrases, or sign anything until this is resolved. " +
            "Reinstall MEGA from its original verified download and try again; if it fails " +
            "again, this device should not be used as a signer.",
    )
}

/**
 * Blocking replacement for the entire app UI when the boot self-test
 * fails. Deliberately not DISMISSIBLE — a broken device must not be
 * one tap away from the app's normal screens — but exitable via
 * "Close app" (non-dismissible must never mean non-exitable: force-kill
 * would erase the message and teach the user that safety dialogs are
 * obstacles), and retryable, because a transient resource fault
 * deserves one honest re-run of a check that is pure and deterministic.
 * Text is scrollable so the full guidance is reachable at large font
 * scales on small screens. Fully TalkBack-readable (plain Text nodes)
 * and copyable as a whole for offline troubleshooting.
 */
@Composable
fun SelfTestFailedDialog(
    failedCheckNames: List<String>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val message = buildSelfTestFailureMessage(failedCheckNames)
    val clipboard = LocalClipboardManager.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
    AlertDialog(
        onDismissRequest = { /* no-op: a failed safety gate cannot be dismissed */ },
        title = { Text("Safety check failed") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message)
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text("Try again")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { clipboard.setText(AnnotatedString(message)) }) {
                    Text("Copy report")
                }
                TextButton(onClick = onClose) {
                    Text("Close app")
                }
            }
        },
    )
}
