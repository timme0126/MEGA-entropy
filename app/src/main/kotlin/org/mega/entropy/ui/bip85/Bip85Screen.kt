package org.mega.entropy.ui.bip85

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaLabelSessionDialog
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPassphraseCard
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaQrCode
import org.mega.entropy.ui.components.MegaSavedConfirmationCard
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.Bip85DerivedMnemonic
import org.mega.entropycore.Bip85MnemonicWords
import org.mega.entropycore.WalletAccountKeys
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.WalletScriptType
import org.mega.entropycore.deriveBip85Bip39Mnemonic
import org.mega.entropycore.deriveWalletAccountKeys

/**
 * Derives a BIP85 child mnemonic and, right below it, its account-level
 * wallet public keys — one screen instead of a "reveal, then navigate
 * elsewhere and re-decide a passphrase" chain. A BIP85 child is a fresh,
 * standalone mnemonic (that's the entire point of BIP85), so wallet-key
 * derivation here always uses an empty passphrase, with no field offered
 * to change that.
 *
 * The PARENT passphrase, by contrast, was already decided once on
 * AdvancedModeHubScreen — this screen only ever displays it (masked, with
 * a reveal toggle) so the user can double check what's actually being
 * used for the derivation. There's no way to edit it here: re-typing the
 * same decision on a second screen invites it to drift from what was
 * actually entered upstream.
 */
@Composable
fun Bip85Screen(
    parentWords: List<String>,
    parentPassphrase: String = "",
    // Non-null only when parentWords came from "Import from Saved
    // Session" — used purely to describe the child ("Child Seed of
    // <parentLabel>") when it's saved; possibly blank if that session was
    // never labeled.
    parentLabel: String? = null,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onBack: () -> Unit,
    // Same save-as-session behavior as AdvancedModeHubScreen's save icon
    // (label dialog, PIN-before-save rule) — only the child words are
    // ever saved, never the parent or child passphrase. childSeedInfo is
    // the auto-generated "Child Seed of ... · script type · index" note
    // this composable builds for the saved session's list description.
    onSaveChildAsSession: (words: List<String>, label: String, childSeedInfo: String) -> Unit,
    savedConfirmationLabel: String?,
    onSavedConfirmationDismissed: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var selectedWords by remember { mutableStateOf(Bip85MnemonicWords.TWELVE) }
    var indexText by remember { mutableStateOf("0") }
    var result by remember { mutableStateOf<Bip85DerivedMnemonic?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingSaveChild by remember { mutableStateOf(false) }
    // True once THIS calculated child has been saved as a session — hides
    // the save icon so it can't be saved a second time as a duplicate.
    // Reset whenever a new/different child is calculated.
    var childSaved by remember { mutableStateOf(false) }

    var scriptType by remember { mutableStateOf(WalletScriptType.NATIVE_SEGWIT) }
    var network by remember { mutableStateOf(WalletNetwork.MAINNET) }
    var accountText by remember { mutableStateOf("0") }
    var walletResult by remember { mutableStateOf<WalletAccountKeys?>(null) }
    var walletError by remember { mutableStateOf<String?>(null) }

    fun clearWalletResult() {
        walletResult = null
        walletError = null
    }

    fun clearResult() {
        result = null
        error = null
        childSaved = false
        clearWalletResult()
    }

    MegaInfoScaffold(title = "BIP85 Child Mnemonic", onBack = onBack) {
        if (savedConfirmationLabel != null) {
            MegaSavedConfirmationCard(savedConfirmationLabel, onSavedConfirmationDismissed)
        }

        Text(
            text = "Derive a deterministic child BIP39 mnemonic from this parent mnemonic. " +
                "The child is reproducible from the same parent, parent passphrase, word count, and index.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard {
            Text(
                text = "Do not treat BIP85 as new dice entropy. Every child mnemonic inherits trust from the parent mnemonic and passphrase.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
                fontWeight = FontWeight.SemiBold,
            )
        }

        MegaCard(title = "Child size") {
            Bip85WordsOption(
                label = "12 words",
                selected = selectedWords == Bip85MnemonicWords.TWELVE,
                onClick = {
                    selectedWords = Bip85MnemonicWords.TWELVE
                    clearResult()
                },
            )
            Bip85WordsOption(
                label = "24 words",
                selected = selectedWords == Bip85MnemonicWords.TWENTY_FOUR,
                onClick = {
                    selectedWords = Bip85MnemonicWords.TWENTY_FOUR
                    clearResult()
                },
            )
        }

        OutlinedTextField(
            value = indexText,
            onValueChange = { value ->
                indexText = value.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                clearResult()
            },
            label = { Text("BIP85 index") },
            supportingText = { Text("Allowed range: 0 to 2147483647") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        MegaPassphraseCard(parentPassphrase)

        val currentError = error
        if (currentError != null) {
            Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
        }

        MegaPrimaryButton(
            text = "Calculate BIP85 Child Mnemonic",
            onClick = {
                val index = indexText.toLongOrNull()
                if (index == null || index !in 0..2_147_483_647L) {
                    error = "Index must be between 0 and 2147483647."
                    result = null
                    clearWalletResult()
                } else {
                    try {
                        result = deriveBip85Bip39Mnemonic(
                            parentWords = parentWords,
                            childWords = selectedWords,
                            index = index,
                            parentPassphrase = parentPassphrase,
                        )
                        error = null
                        clearWalletResult()
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Could not derive BIP85 child mnemonic."
                        result = null
                        clearWalletResult()
                    }
                }
            },
        )

        val currentResult = result
        if (currentResult != null) {
            MegaCard(title = "BIP85 derivation path") {
                MegaMonoText(currentResult.path)
            }
            MegaCard(title = "Child entropy") {
                MegaMonoText(currentResult.entropy.hex)
            }
            MegaCard(
                title = "Child mnemonic",
                trailingAction = {
                    Row {
                        if (allowSeedCopy) {
                            MegaCopyIconButton(
                                contentDescription = "Copy child words",
                                getTextToCopy = { currentResult.mnemonicWords.joinToString(" ") },
                            )
                        }
                        if (!childSaved) {
                            IconButton(onClick = { confirmingSaveChild = true }) {
                                Icon(Icons.Filled.Save, contentDescription = "Save child mnemonic as session")
                            }
                        }
                    }
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    currentResult.mnemonicWords.forEachIndexed { index, word ->
                        MegaMonoText("${(index + 1).toString().padStart(2, '0')}. $word")
                    }
                }
            }

            if (confirmingSaveChild) {
                MegaLabelSessionDialog(
                    onConfirm = { label ->
                        val childSeedInfo = buildString {
                            append("Child Seed")
                            if (!parentLabel.isNullOrBlank()) {
                                append(" of \"$parentLabel\"")
                            }
                            append(" · ${scriptType.displayName}")
                            append(" · Index ${currentResult.index}")
                            append(" · Passphrase: ${if (parentPassphrase.isNotEmpty()) "Yes" else "No"}")
                        }
                        onSaveChildAsSession(currentResult.mnemonicWords, label, childSeedInfo)
                        childSaved = true
                        confirmingSaveChild = false
                    },
                    onDismiss = { confirmingSaveChild = false },
                )
            }

            MegaCard(title = "Script type") {
                WalletScriptType.entries.forEach { type ->
                    RadioOption(
                        label = "${type.displayName} — BIP${type.bipNumber}",
                        selected = scriptType == type,
                        onClick = { scriptType = type; clearWalletResult() },
                    )
                }
            }

            MegaCard(title = "Network") {
                RadioOption(
                    label = "Mainnet",
                    selected = network == WalletNetwork.MAINNET,
                    onClick = { network = WalletNetwork.MAINNET; clearWalletResult() },
                )
                RadioOption(
                    label = "Testnet",
                    selected = network == WalletNetwork.TESTNET,
                    onClick = { network = WalletNetwork.TESTNET; clearWalletResult() },
                )
            }

            OutlinedTextField(
                value = accountText,
                onValueChange = { value ->
                    accountText = value.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                    clearWalletResult()
                },
                label = { Text("Account index") },
                supportingText = { Text("Usually 0 unless you're intentionally using a separate account") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            val currentWalletError = walletError
            if (currentWalletError != null) {
                Text(currentWalletError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
            }

            MegaPrimaryButton(
                text = "Derive Public Keys",
                onClick = {
                    val account = accountText.toIntOrNull()
                    if (account == null) {
                        walletError = "Enter a valid account index."
                        walletResult = null
                    } else {
                        try {
                            walletResult = deriveWalletAccountKeys(
                                currentResult.mnemonicWords,
                                "",
                                scriptType,
                                network,
                                account,
                            )
                            walletError = null
                        } catch (e: IllegalArgumentException) {
                            walletError = e.message ?: "Could not derive account keys."
                            walletResult = null
                        }
                    }
                },
            )

            val currentWalletResult = walletResult
            if (currentWalletResult != null) {
                MegaCard(title = "Wallet derivation path") {
                    MegaMonoText(currentWalletResult.derivationPath)
                }

                MegaCard(
                    title = "Master Fingerprint",
                    trailingAction = if (allowSeedCopy) {
                        { MegaCopyIconButton(contentDescription = "Copy master fingerprint", getTextToCopy = { currentWalletResult.masterFingerprint }) }
                    } else {
                        null
                    },
                ) {
                    MegaMonoText(currentWalletResult.masterFingerprint)
                }

                MegaCard {
                    Text(
                        "Exporting this extended public key reveals every address and the " +
                            "full transaction history for this account to whoever holds it. " +
                            "It cannot spend funds, but treat it as sensitive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MegaError,
                    )
                }

                MegaCard(
                    title = "Extended public key",
                    trailingAction = if (allowSeedCopy) {
                        { MegaCopyIconButton(contentDescription = "Copy extended public key", getTextToCopy = { currentWalletResult.extendedPublicKey }) }
                    } else {
                        null
                    },
                ) {
                    MegaMonoText(currentWalletResult.extendedPublicKey)
                }

                MegaCard(title = "QR code (public account data only)") {
                    MegaQrCode(currentWalletResult.extendedPublicKey)
                }

                MegaCard(
                    title = "First receive address (external chain, index 0)",
                    trailingAction = if (allowSeedCopy) {
                        { MegaCopyIconButton(contentDescription = "Copy first receive address", getTextToCopy = { currentWalletResult.firstReceiveAddress }) }
                    } else {
                        null
                    },
                ) {
                    MegaMonoText(currentWalletResult.firstReceiveAddress)
                }
            }
        }
    }
}

@Composable
private fun Bip85WordsOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
