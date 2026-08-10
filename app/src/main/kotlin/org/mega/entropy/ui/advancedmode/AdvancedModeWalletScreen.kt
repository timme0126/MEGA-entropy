package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPassphraseCard
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaQrCode
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.WalletAccountKeys
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.WalletReceivePrivateKey
import org.mega.entropycore.WalletScriptType
import org.mega.entropycore.deriveWalletAccountKeys
import org.mega.entropycore.deriveWalletReceivePrivateKey

/**
 * Advanced Mode wallet-derivation tool (spec "Advanced Mode wallet
 * derivation tools"): account-level xpub/ypub/zpub plus the first receive
 * address, for cross-checking against another wallet — never a private
 * key or signing capability. Taproot (BIP86) is deferred; see
 * WalletScriptType's KDoc in entropy-core for why.
 *
 * [passphrase] was already decided once on AdvancedModeHubScreen — this
 * screen only displays it (masked, with a reveal toggle) and uses it
 * as-is for every derivation below. There's no field to re-type or edit
 * it: a second editable copy of the same decision invites it to silently
 * drift from what was actually entered upstream.
 */
@Composable
fun AdvancedModeWalletScreen(
    mnemonicWords: List<String>,
    passphrase: String = "",
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    allowPrivateKeyExport: Boolean,
    onBack: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var scriptType by remember { mutableStateOf(WalletScriptType.NATIVE_SEGWIT) }
    var network by remember { mutableStateOf(WalletNetwork.MAINNET) }
    var accountText by remember { mutableStateOf("0") }
    var result by remember { mutableStateOf<WalletAccountKeys?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var privateKeyResult by remember { mutableStateOf<WalletReceivePrivateKey?>(null) }
    var confirmingPrivateKeyExport by remember { mutableStateOf(false) }
    var privateKeyError by remember { mutableStateOf<String?>(null) }

    fun clearResult() {
        result = null
        error = null
        privateKeyResult = null
        privateKeyError = null
    }

    MegaInfoScaffold(title = "Wallet Public Keys", onBack = onBack) {
        MegaPassphraseCard(passphrase)

        MegaCard(title = "Script type") {
            WalletScriptType.entries.forEach { type ->
                ScriptTypeOption(
                    label = "${type.displayName} — BIP${type.bipNumber}",
                    selected = scriptType == type,
                    onClick = { scriptType = type; clearResult() },
                )
            }
        }

        MegaCard(title = "Network") {
            NetworkOption(
                label = "Mainnet",
                selected = network == WalletNetwork.MAINNET,
                onClick = { network = WalletNetwork.MAINNET; clearResult() },
            )
            NetworkOption(
                label = "Testnet",
                selected = network == WalletNetwork.TESTNET,
                onClick = { network = WalletNetwork.TESTNET; clearResult() },
            )
        }

        OutlinedTextField(
            value = accountText,
            onValueChange = { value ->
                accountText = value.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                clearResult()
            },
            label = { Text("Account index") },
            supportingText = { Text("Usually 0 unless you're intentionally using a separate account") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        val currentError = error
        if (currentError != null) {
            Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
        }

        MegaPrimaryButton(
            text = "Derive Public Keys",
            onClick = {
                val account = accountText.toIntOrNull()
                if (account == null) {
                    error = "Enter a valid account index."
                    result = null
                } else {
                    try {
                        result = deriveWalletAccountKeys(mnemonicWords, passphrase, scriptType, network, account)
                        error = null
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Could not derive account keys."
                        result = null
                    }
                }
            },
        )

        val currentResult = result
        if (currentResult != null) {
            MegaCard(title = "Derivation path") {
                MegaMonoText(currentResult.derivationPath)
            }

            MegaCard(
                title = "Master Fingerprint",
                trailingAction = if (allowSeedCopy) {
                    { MegaCopyIconButton(contentDescription = "Copy master fingerprint", getTextToCopy = { currentResult.masterFingerprint }) }
                } else {
                    null
                },
            ) {
                MegaMonoText(currentResult.masterFingerprint)
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
                    { MegaCopyIconButton(contentDescription = "Copy extended public key", getTextToCopy = { currentResult.extendedPublicKey }) }
                } else {
                    null
                },
            ) {
                MegaMonoText(currentResult.extendedPublicKey)
            }

            MegaCard(title = "QR code (public account data only)") {
                MegaQrCode(currentResult.extendedPublicKey, contentDescription = "QR code for extended public key")
            }

            MegaCard(
                title = "First receive address (external chain, index 0)",
                trailingAction = if (allowSeedCopy) {
                    { MegaCopyIconButton(contentDescription = "Copy first receive address", getTextToCopy = { currentResult.firstReceiveAddress }) }
                } else {
                    null
                },
            ) {
                MegaMonoText(currentResult.firstReceiveAddress)
            }

            if (allowPrivateKeyExport) {
                MegaCard {
                    Text(
                        "Danger Zone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "A private key can spend whatever funds are sent to that one " +
                            "address — unlike the public data above, anyone who sees it, " +
                            "or any copy of it, can take them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val currentPrivateKeyError = privateKeyError
                if (currentPrivateKeyError != null) {
                    Text(currentPrivateKeyError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
                }

                val currentPrivateKeyResult = privateKeyResult
                if (currentPrivateKeyResult == null) {
                    MegaPrimaryButton(
                        text = "Generate Private Key (WIF)",
                        onClick = { confirmingPrivateKeyExport = true },
                    )
                } else {
                    MegaCard(
                        title = "Private key (WIF)",
                        trailingAction = if (allowSeedCopy) {
                            { MegaCopyIconButton(contentDescription = "Copy private key", getTextToCopy = { currentPrivateKeyResult.wif }) }
                        } else {
                            null
                        },
                    ) {
                        MegaMonoText(currentPrivateKeyResult.derivationPath)
                        MegaMonoText(currentPrivateKeyResult.wif)
                    }

                    MegaCard {
                        Text(
                            "Scanning this QR code sweeps the same private key shown above " +
                                "— e.g. Sparrow Wallet's \"Sweep Private Key\" — into whatever " +
                                "app or camera scans it. Only reveal it to a wallet you intend " +
                                "to sweep these funds into.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MegaError,
                        )
                    }
                    MegaCard(title = "QR code (WIF private key)") {
                        MegaQrCode(currentPrivateKeyResult.wif, contentDescription = "QR code for WIF private key")
                    }

                    MegaCard(
                        title = "Output descriptor",
                        trailingAction = if (allowSeedCopy) {
                            { MegaCopyIconButton(contentDescription = "Copy output descriptor", getTextToCopy = { currentPrivateKeyResult.descriptor }) }
                        } else {
                            null
                        },
                    ) {
                        MegaMonoText(currentPrivateKeyResult.descriptor)
                    }
                }
            }
        }
    }

    if (confirmingPrivateKeyExport) {
        val account = accountText.toIntOrNull()
        AlertDialog(
            onDismissRequest = { confirmingPrivateKeyExport = false },
            title = { Text("Generate Private Key?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This reveals a WIF private key for the first receive address " +
                            "shown above. That key alone can spend any funds sent to that " +
                            "address — treat it exactly like the funds themselves.",
                    )
                    Text(
                        "Anyone who sees this key, or any copy of it (screenshot, " +
                            "clipboard, screen share), can take those funds. Only " +
                            "continue if you understand and accept that risk.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (account == null) {
                        privateKeyError = "Enter a valid account index."
                    } else {
                        try {
                            privateKeyResult = deriveWalletReceivePrivateKey(mnemonicWords, passphrase, scriptType, network, account)
                            privateKeyError = null
                        } catch (e: IllegalArgumentException) {
                            privateKeyError = e.message ?: "Could not generate the private key."
                        }
                    }
                    confirmingPrivateKeyExport = false
                }) {
                    Text("Generate", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingPrivateKeyExport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ScriptTypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NetworkOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
