package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaLabelSessionDialog
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropycore.MultisigWallet

/**
 * Read-only view of an already-saved multisig vault, reached from
 * SavedMultisigVaultsScreen's "View" button. Reuses
 * MultisigVaultResultDisplay — the exact same descriptor/QR/address/
 * cosigner-tile rendering the freshly-built Result step shows — so a saved
 * vault looks identical to how it looked the moment it was built. No
 * "Save" action here (it's already saved); Rename/Delete replace it in the
 * top bar, alongside the same "Create PDF" action the Result step offers.
 */
@Composable
fun SavedMultisigVaultDetailScreen(
    label: String,
    wallet: MultisigWallet,
    cosigners: List<CosignerDisplayInfo>,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onExportPdf: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    MegaInfoScaffold(
        title = label,
        onBack = onBack,
        actions = {
            IconButton(onClick = { renaming = true }) {
                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Rename vault")
            }
            IconButton(onClick = onExportPdf) {
                Icon(imageVector = Icons.Filled.PictureAsPdf, contentDescription = "Create PDF")
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete vault")
            }
        },
    ) {
        MultisigVaultResultDisplay(wallet = wallet, cosigners = cosigners, allowSeedCopy = allowSeedCopy)
    }

    if (renaming) {
        MegaLabelSessionDialog(
            title = "Label This Vault",
            helperText = "A label is required so this vault can be told apart from others later.",
            initialLabel = label,
            onConfirm = { newLabel ->
                onRename(newLabel)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Are you sure?") },
            text = { Text("This permanently deletes this saved vault. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}
