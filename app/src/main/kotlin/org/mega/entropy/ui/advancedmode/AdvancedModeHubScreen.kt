package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.Bip39Seed
import org.mega.entropycore.deriveSeed

/**
 * Landing point once a mnemonic is loaded — typed in on
 * AdvancedModeMnemonicEntryScreen or brought in via "Import from Saved
 * Session" — where the passphrase question is asked exactly once, in one
 * place, regardless of how the words got here: choose what to derive
 * from them. Reveal Seed Words lets the words themselves be
 * double-checked here too — e.g. against a physical backup — before
 * deciding to save or derive anything from them. The words themselves
 * stay held in MegaNavGraph's in-memory state and are cleared as soon as
 * this whole Advanced Mode branch is left, same lifetime as saved-session
 * BIP85 words — unless the save icon is used to explicitly write them to
 * encrypted saved-session storage as a new dice-roll-less session (only
 * the words, never the passphrase below).
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
    onSignPsbt: (passphrase: String) -> Unit,
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
    var wordsRevealed by remember { mutableStateOf(false) }

    MegaInfoScaffold(
        title = "Advanced Mode",
        // Revealed seed words are the most sensitive thing this screen can
        // show — Back should close them first and leave the session loaded
        // right here, the same as the "Hide seed words" link below, rather
        // than reading as "leave Advanced Mode" while they're still on
        // screen. Only navigate away once they're already hidden.
        onBack = {
            if (wordsRevealed) {
                wordsRevealed = false
            } else {
                onBack()
            }
        },
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

        if (!wordsRevealed) {
            MegaCard {
                Text(
                    "Anyone who sees these seed words may be able to take funds from any wallet initialized with them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError,
                )
            }
            MegaPrimaryButton(text = "Reveal Seed Words", onClick = { wordsRevealed = true })
        } else {
            MegaCard(
                title = "Seed Words",
                trailingAction = if (allowSeedCopy) {
                    {
                        MegaCopyIconButton(
                            contentDescription = "Copy seed words",
                            getTextToCopy = { mnemonicWords.joinToString(" ") },
                        )
                    }
                } else {
                    null
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    mnemonicWords.forEachIndexed { index, word ->
                        MegaMonoText("${(index + 1).toString().padStart(2, '0')}. $word")
                    }
                }
            }
            Text(
                text = "Hide seed words",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { wordsRevealed = false },
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
                // Password keyboard type + no autocorrect: a BIP39 passphrase is
                // secret, case-sensitive input where autocorrect/autocapitalize
                // could silently mutate the derivation seed, and a password-type
                // keyboard both discourages IME word suggestions/learning and
                // matches the masked visualTransformation above.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
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
        MegaPrimaryButton(text = "Sign PSBT", onClick = { onSignPsbt(passphrase) })

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
