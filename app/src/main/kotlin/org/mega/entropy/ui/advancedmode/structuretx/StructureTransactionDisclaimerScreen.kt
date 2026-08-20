package org.mega.entropy.ui.advancedmode.structuretx

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Mandatory acknowledgment shown before the camera opens for "Structure a
 * Transaction" — MEGA has no node connection and never reads a wallet's
 * balance or UTXO set on its own; the ONLY UTXOs it can ever structure
 * with are the ones actually present as inputs in whatever PSBT gets
 * scanned next. A user who doesn't realize this could scan a transaction
 * that only spends part of their balance (because their wallet's coin
 * selection didn't include everything they meant to structure) and never
 * know MEGA had no way to tell. Requires an explicit checkbox, not just a
 * warning card, before "Continue to Scan" is reachable — the same
 * "agree, don't just read" bar as revealing seed words or a private key.
 */
@Composable
fun StructureTransactionDisclaimerScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onContinueToScan: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    var acknowledged by remember { mutableStateOf(false) }

    MegaInfoScaffold(title = "Before You Scan", onBack = onBack) {
        MegaCard(title = "MEGA Only Sees What's In The Scan") {
            Text(
                "MEGA is offline and has no connection to the Bitcoin network — it never reads your " +
                    "wallet's balance or UTXO set from a node. The only UTXOs it can structure a " +
                    "transaction with are the ones actually included as inputs in the PSBT you scan next, " +
                    "built by your other wallet (e.g. Sparrow).",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
            )
        }

        MegaCard(title = "Before You Build That Transaction") {
            Text(
                "You can include multiple UTXOs in the PSBT, and MEGA will use all of them. In your " +
                    "wallet, before creating it:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "• Make sure every UTXO you want structured here is selected/included.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "• Consider freezing any UTXOs you do NOT want included, so automatic coin selection " +
                    "doesn't leave them out — or pull in ones you didn't intend.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Any balance not included as an input in the scanned PSBT will not be part of the " +
                    "structured transaction — MEGA has no way to know it exists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            Text(
                "I understand MEGA can only use the UTXOs included in the PSBT I'm about to scan.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (acknowledged) {
            MegaPrimaryButton(text = "Continue to Scan", onClick = onContinueToScan)
        }
        MegaSecondaryButton(text = "Cancel", onClick = onBack)
    }
}
