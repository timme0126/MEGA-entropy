package org.mega.entropy.ui.selftest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

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
 * fails. Deliberately not dismissible — a broken device must not be
 * one tap away from the app's normal screens — but fully readable by
 * TalkBack (plain Text nodes) and copyable as a whole for offline
 * troubleshooting.
 */
@Composable
fun SelfTestFailedDialog(failedCheckNames: List<String>) {
    val message = buildSelfTestFailureMessage(failedCheckNames)
    val clipboard = LocalClipboardManager.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
    AlertDialog(
        onDismissRequest = { /* no-op: a failed safety gate cannot be dismissed */ },
        title = { Text("Safety check failed") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(message)) }) {
                Text("Copy report")
            }
        },
    )
}
