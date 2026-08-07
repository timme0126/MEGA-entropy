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
                    "Optionally add a MEGA PIN as an extra access barrier",
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
                ),
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
            Text("•  $item", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
