package org.mega.entropy.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold

/** Spec section 26, "Security Model page" — a plain-English threat model. */
@Composable
fun SecurityModelScreen(onBack: () -> Unit) {
    MegaInfoScaffold(title = "Security Model", onBack = onBack) {
        Text(
            "MEGA is a tool, not a guarantee. Below is what it actually " +
                "protects against, and — just as important — what it does " +
                "not. This software is experimental until independently " +
                "audited; see About.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard(title = "MEGA does") {
            BulletList(
                listOf(
                    "Derive mnemonic entropy only from the dice you enter — nothing else",
                    "Work without any networking",
                    "Request no INTERNET permission — it isn't in the manifest",
                    "Keep saved data inside this app's private sandbox",
                    "Encrypt any data you explicitly choose to save",
                    "Require a MEGA PIN before any session can be saved or viewed",
                    "Optionally save a way to verify a re-entered passphrase, without ever storing or displaying it",
                    "Suppress screenshots and recent-app thumbnails on sensitive screens",
                    "Show every intermediate calculation, not just the final words",
                    "Allow independent verification of every step, by design",
                ),
            )
        }

        MegaCard(title = "MEGA does not protect against") {
            BulletList(
                listOf(
                    "A compromised Android OS",
                    "Malicious firmware",
                    "Someone watching you type your dice or your seed",
                    "Hidden cameras",
                    "A malicious or biased physical die",
                    "Someone obtaining your written-down seed",
                    "A compromised keyboard, if one is ever used elsewhere with your seed",
                    "Flaws in your own physical entropy procedure",
                    "Undiscovered vulnerabilities in Android or the hardware itself",
                    "A rooted or otherwise administratively-compromised device",
                    "Offline passphrase brute-forcing if a saved passphrase check's encryption is ever defeated",
                ),
            )
        }

        MegaCard(title = "Best Practice: GrapheneOS") {
            Text(
                "MEGA works on stock Android, but GrapheneOS is the stronger " +
                    "choice for actually protecting funds with it — it hardens " +
                    "exactly the parts of the device MEGA has no control over. " +
                    "A hardened memory allocator limits the damage of " +
                    "memory-corruption bugs, in MEGA or the OS itself. No " +
                    "Google Play Services by default removes a layer of " +
                    "background telemetry MEGA can't see or block from inside " +
                    "the app. A per-app network toggle lets you disable MEGA's " +
                    "network access at the OS level and independently confirm " +
                    "it still works identically — proof, not just trust, that " +
                    "\"no INTERNET permission\" really means no network " +
                    "activity. And verified boot comes from a security-focused " +
                    "non-profit instead of an OEM's bundled software. None of " +
                    "this replaces MEGA's own guarantees — it closes the gap " +
                    "between \"this app is offline\" and \"this device is " +
                    "trustworthy enough for that to matter.\"",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            "MEGA is not marketed as unhackable, because nothing is.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Text("• $item", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
