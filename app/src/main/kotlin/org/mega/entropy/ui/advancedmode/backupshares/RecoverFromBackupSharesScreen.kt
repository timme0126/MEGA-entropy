package org.mega.entropy.ui.advancedmode.backupshares

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropycore.BackupShare
import org.mega.entropycore.backupSharesToEntropy
import org.mega.entropycore.decodeBackupShare
import org.mega.entropycore.deriveMnemonicFromEntropy

/**
 * The recovery side of the entropy-rooted backup pattern (see
 * EntropyBackupShares.kt in entropy-core): type in [threshold] or more
 * backup shares created earlier by BackupShareRevealScreen, and recover
 * the exact same mnemonic those shares were split from — landing on
 * Advanced Mode's Hub exactly like every other way of getting a mnemonic
 * into MEGA (manual entry, saved-session import, SeedQR).
 *
 * Unlike share CREATION, which deliberately never shows more than one
 * share on screen at a time, recovery necessarily brings the needed
 * shares together on this device — that's what "recover" means, no
 * different from typing in a full mnemonic on
 * AdvancedModeMnemonicEntryScreen. What's typed here lives only in this
 * screen's local state and is never persisted.
 */
@Composable
fun RecoverFromBackupSharesScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onRecovered: (List<String>) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var wordCount by remember { mutableStateOf(24) }
    val shareInputs = remember { mutableStateListOf("", "") }
    var error by remember { mutableStateOf<String?>(null) }

    MegaInfoScaffold(title = "Recover From Backup Shares", onBack = onBack) {
        MegaCard(title = "Mnemonic Length") {
            Text(
                "Choose the length of the mnemonic these shares were originally split from.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioButton(selected = wordCount == 12, onClick = { wordCount = 12 })
                Text("12 words", modifier = androidx.compose.ui.Modifier.padding(end = 16.dp))
                RadioButton(selected = wordCount == 24, onClick = { wordCount = 24 })
                Text("24 words")
            }
        }

        MegaCard(title = "Enter Backup Shares") {
            Text(
                "Enter at least as many shares as the threshold they were created with — check any " +
                    "one share's own text if you don't remember the number.",
                style = MaterialTheme.typography.bodyMedium,
            )
            shareInputs.forEachIndexed { index, value ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { shareInputs[index] = it; error = null },
                    label = { Text("Share ${index + 1}") },
                    singleLine = true,
                    modifier = androidx.compose.ui.Modifier.padding(vertical = 2.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MegaSecondaryButton(text = "Add Another Share", onClick = { shareInputs.add("") })
                if (shareInputs.size > 2) {
                    MegaSecondaryButton(
                        text = "Remove Last",
                        onClick = { shareInputs.removeAt(shareInputs.size - 1) },
                    )
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        MegaPrimaryButton(
            text = "Recover",
            onClick = {
                val result = runCatching {
                    val decoded: List<BackupShare> = shareInputs
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { decodeBackupShare(it) }
                    require(decoded.isNotEmpty()) { "Enter at least one backup share." }
                    val expectedByteLength = if (wordCount == 12) 16 else 32
                    val entropy = backupSharesToEntropy(decoded, expectedByteLength)
                    deriveMnemonicFromEntropy(entropy.bytes)
                }
                result.fold(
                    onSuccess = { words -> onRecovered(words) },
                    onFailure = { e -> error = e.message ?: "Could not recover a mnemonic from these shares." },
                )
            },
        )
    }
}
