package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaLabelSessionDialog
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSavedConfirmationCard
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropycore.Bip39Seed
import org.mega.entropycore.deriveSeed

/**
 * Landing point once a mnemonic is loaded — either typed in on
 * AdvancedModeMnemonicEntryScreen or brought in via "Import from Saved
 * Session" — where the passphrase question is asked exactly once, in one
 * place, regardless of how the words got here: choose what to derive from
 * them. The words themselves stay held in MegaNavGraph's in-memory state
 * and are cleared as soon as this whole Advanced Mode branch is left, same
 * lifetime as saved-session BIP85 words — unless the save icon is used to
 * explicitly write them to encrypted saved-session storage as a new
 * dice-roll-less session (only the words, never the passphrase below).
 *
 * The passphrase typed here is read fresh at the moment each button below
 * is pressed — leaving it blank derives from the words alone, exactly like
 * BIP39's own "no passphrase" case.
 */
@Composable
fun AdvancedModeHubScreen(
    mnemonicWords: List<String>,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    // True when these words were loaded via "Import from Saved Session" —
    // they already exist as a saved session, so offering to save them
    // again as a brand new one would just create a duplicate.
    isExistingSavedSession: Boolean,
    onBack: () -> Unit,
    onBip85: (passphrase: String) -> Unit,
    onWalletKeys: (passphrase: String) -> Unit,
    // Only the words are ever saved — never the passphrase typed below,
    // matching every other saved session in the app. label comes from the
    // dialog opened by the top-right save icon.
    onSaveAsSession: (label: String) -> Unit,
    savedConfirmationLabel: String?,
    onSavedConfirmationDismissed: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var revealedSeed by remember { mutableStateOf<Bip39Seed?>(null) }
    var confirmingSave by remember { mutableStateOf(false) }

    MegaInfoScaffold(
        title = "Advanced Mode",
        onBack = onBack,
        actions = {
            if (!isExistingSavedSession) {
                IconButton(onClick = { confirmingSave = true }) {
                    Icon(Icons.Filled.Save, contentDescription = "Save as session")
                }
            }
        },
    ) {
        if (savedConfirmationLabel != null) {
            MegaSavedConfirmationCard(savedConfirmationLabel, onSavedConfirmationDismissed)
        }

        MegaCard {
            Text(
                "${mnemonicWords.size}-word seed phrase entered. Held in memory only for this " +
                    "screen — nothing is saved to disk unless you tap the save icon above.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        MegaCard(title = "Passphrase (optional)") {
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    revealedSeed = null
                },
                label = { Text("BIP39 passphrase, if used") },
                singleLine = true,
                visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (showPassphrase) "Hide passphrase" else "Show passphrase",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showPassphrase = !showPassphrase },
            )
            Text(
                text = "Leave blank to derive from the seed words alone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MegaPrimaryButton(text = "Derive Wallet Public Keys", onClick = { onWalletKeys(passphrase) })
        MegaPrimaryButton(text = "Derive BIP85 Child Mnemonic", onClick = { onBip85(passphrase) })

        val seed = revealedSeed
        if (seed == null) {
            MegaPrimaryButton(
                text = "Reveal BIP39 Seed",
                onClick = { revealedSeed = deriveSeed(mnemonicWords, passphrase) },
            )
        } else {
            MegaCard(
                title = "BIP39 Seed (512-bit, hex)",
                trailingAction = if (allowSeedCopy) {
                    {
                        MegaCopyIconButton(
                            contentDescription = "Copy BIP39 seed",
                            getTextToCopy = { seed.hex },
                        )
                    }
                } else {
                    null
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    seed.hex.chunked(32).forEach { line -> MegaMonoText(line) }
                }
            }
            Text(
                text = "Hide seed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { revealedSeed = null },
            )
        }
    }

    if (confirmingSave) {
        MegaLabelSessionDialog(
            onConfirm = { label ->
                onSaveAsSession(label)
                confirmingSave = false
            },
            onDismiss = { confirmingSave = false },
        )
    }
}
