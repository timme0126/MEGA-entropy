package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.WalletNetwork

/**
 * Shown instead of silently applying a scanned/pasted descriptor when it
 * would replace cosigner slots the user already filled — see
 * MultisigVaultViewModel.fillManySlotsFromDescriptor's doc comment for why.
 * "Replace Cosigners" is the destructive action (discards the user's
 * existing, possibly already-verified cosigner slots), so it gets this
 * codebase's outlined/secondary styling; "Cancel" — the safe default that
 * leaves everything untouched — gets the orange primary styling.
 */
@Composable
fun DescriptorImportConfirmationDialog(
    pending: PendingDescriptorImport,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Replace Cosigners?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "This will replace your current cosigner setup.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The scanned or pasted descriptor describes a different vault: " +
                        "${pending.threshold}-of-${pending.cosignerCount} on ${networkLabel(pending.network)}. " +
                        "Continuing will replace the signature policy (M-of-N), network, and every cosigner " +
                        "slot you've already filled in — including any you verified — with the ones from " +
                        "this descriptor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "If you did not intend to scan or paste a different vault's descriptor, tap Cancel — " +
                        "your existing cosigners are untouched.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError,
                )
                Spacer(modifier = Modifier.height(12.dp))
                MegaSecondaryButton(text = "Replace Cosigners", onClick = onConfirm)
                Spacer(modifier = Modifier.height(4.dp))
                MegaPrimaryButton(text = "Cancel", onClick = onCancel)
            }
        },
        confirmButton = {},
    )
}

private fun networkLabel(network: WalletNetwork): String = when (network) {
    WalletNetwork.MAINNET -> "mainnet"
    WalletNetwork.TESTNET -> "testnet"
}
