package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.mega.entropy.storage.SavedMultisigVault
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaLabelSessionDialog
import org.mega.entropy.ui.components.MegaNeutralButton
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.savedsessions.parseTagsInput
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

@Composable
fun SavedMultisigVaultsScreen(
    vaults: List<SavedMultisigVault>,
    isLoading: Boolean,
    allTags: List<String>,
    selectedTagFilters: Set<String>,
    onTagFilterToggled: (String) -> Unit,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onViewVault: (id: String) -> Unit,
    onRenameVault: (id: String, label: String) -> Unit,
    onUpdateVaultTags: (id: String, tags: List<String>) -> Unit,
    onDeleteVault: (id: String) -> Unit,
    onCreateNewVault: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    MegaInfoScaffold(title = "Multi-Signature Vaults", onBack = onBack) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else if (vaults.isEmpty() && allTags.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No saved multi-signature vaults yet. Vaults are only saved when you explicitly choose to save one on the Vault Ready screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                MegaPrimaryButton(text = "Create New Multi-Signature Vault", onClick = onCreateNewVault)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (allTags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = tag in selectedTagFilters,
                                onClick = { onTagFilterToggled(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                vaults.forEach { vault ->
                    SavedMultisigVaultCard(
                        vault = vault,
                        onView = { onViewVault(vault.id) },
                        onRename = { newLabel -> onRenameVault(vault.id, newLabel) },
                        onTagsChanged = { tags -> onUpdateVaultTags(vault.id, tags) },
                        onDelete = { onDeleteVault(vault.id) },
                    )
                }
                MegaPrimaryButton(text = "Create New Multi-Signature Vault", onClick = onCreateNewVault)
            }
        }
    }
}

@Composable
private fun SavedMultisigVaultCard(
    vault: SavedMultisigVault,
    onView: () -> Unit,
    onRename: (String) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingTags by remember { mutableStateOf(false) }
    val dateText = remember(vault.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance().format(Date(vault.createdAtEpochMillis))
    }
    val networkLabel = if (vault.network == WalletNetwork.MAINNET) "Mainnet" else "Testnet"

    MegaCard {
        if (vault.label.isNotBlank()) {
            Text(vault.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${vault.threshold}-of-${vault.cosigners.size} multisig · $networkLabel · ${vault.scriptType.displayName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vault.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                vault.tags.forEach { tag ->
                    AssistChip(onClick = { editingTags = true }, label = { Text(tag) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier) {
            MegaNeutralButton(text = "View", modifier = Modifier.weight(1f), onClick = onView)
            MegaNeutralButton(text = "Label", modifier = Modifier.weight(1f), onClick = { renaming = true })
            MegaNeutralButton(text = "Tags", modifier = Modifier.weight(1f), onClick = { editingTags = true })
        }
        MegaSecondaryButton(text = "Delete", onClick = { confirmingDelete = true })
    }

    if (renaming) {
        MegaLabelSessionDialog(
            title = "Label This Vault",
            helperText = "A label is required so this vault can be told apart from others later.",
            initialLabel = vault.label,
            onConfirm = { newLabel -> onRename(newLabel); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    if (editingTags) {
        MegaLabelSessionDialog(
            title = "Edit Tags",
            helperText = "Comma-separated, e.g. cold storage, inheritance.",
            initialLabel = vault.tags.joinToString(", "),
            onConfirm = { text -> onTagsChanged(parseTagsInput(text)); editingTags = false },
            onDismiss = { editingTags = false },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Are you sure?") },
            text = { Text("This permanently deletes this saved vault. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirmingDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}
