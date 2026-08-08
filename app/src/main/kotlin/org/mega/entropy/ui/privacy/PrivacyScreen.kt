package org.mega.entropy.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold

/** Spec section 35, "Privacy policy" — mirrors PRIVACY.md in-app. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    MegaInfoScaffold(title = "Privacy", onBack = onBack) {
        Text(
            "MEGA's privacy model is deliberately simple: there is nothing " +
                "for the developer to collect, because there is no channel " +
                "for it to travel through.",
            style = MaterialTheme.typography.bodyMedium,
        )
        MegaCard {
            val items = listOf(
                "No INTERNET permission — the app cannot make network requests",
                "No analytics",
                "No telemetry",
                "No accounts",
                "No server",
                "No cloud sync",
                "No advertising",
                "No export of dice rolls or mnemonics (copy, share, QR, screenshot, or otherwise)",
                "No collection of user data by the developer, ever",
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Text(
            "Anything you choose to save (dice rolls, and optionally the " +
                "derived mnemonic) stays encrypted in this app's private " +
                "storage on this device, excluded from Android backups. " +
                "See Security Model for details.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
