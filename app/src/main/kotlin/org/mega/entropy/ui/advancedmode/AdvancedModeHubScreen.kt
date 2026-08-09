package org.mega.entropy.ui.advancedmode

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen

/**
 * Landing point once a manually entered mnemonic has validated: choose
 * what to derive from it. The words themselves stay held in MegaNavGraph's
 * in-memory state (never written to disk) and are cleared as soon as this
 * whole Advanced Mode branch is left, same lifetime as saved-session
 * BIP85 words.
 */
@Composable
fun AdvancedModeHubScreen(
    wordCount: Int,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onBip85: () -> Unit,
    onWalletKeys: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    MegaInfoScaffold(title = "Advanced Mode", onBack = onBack) {
        MegaCard {
            Text(
                "$wordCount-word seed phrase entered. Held in memory only for this " +
                    "screen — nothing is saved to disk.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        MegaSecondaryButton(text = "Derive BIP85 Child Mnemonic", onClick = onBip85)
        MegaSecondaryButton(text = "Derive Wallet Account Keys", onClick = onWalletKeys)
    }
}
