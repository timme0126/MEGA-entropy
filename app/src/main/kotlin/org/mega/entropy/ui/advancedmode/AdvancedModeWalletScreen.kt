package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaQrCode
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.WalletAccountKeys
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.WalletScriptType
import org.mega.entropycore.deriveWalletAccountKeys

/**
 * Advanced Mode wallet-derivation tool (spec "Advanced Mode wallet
 * derivation tools"): account-level xpub/ypub/zpub plus the first receive
 * address, for cross-checking against another wallet — never a private
 * key or signing capability. Taproot (BIP86) is deferred; see
 * WalletScriptType's KDoc in entropy-core for why.
 */
@Composable
fun AdvancedModeWalletScreen(
    mnemonicWords: List<String>,
    passphrase: String,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onBack: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var scriptType by remember { mutableStateOf(WalletScriptType.NATIVE_SEGWIT) }
    var network by remember { mutableStateOf(WalletNetwork.MAINNET) }
    var accountText by remember { mutableStateOf("0") }
    var result by remember { mutableStateOf<WalletAccountKeys?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun clearResult() {
        result = null
        error = null
    }

    MegaInfoScaffold(title = "Wallet Account Keys", onBack = onBack) {
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
            text = "Derive Account Keys",
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
                leadingAction = if (allowSeedCopy) {
                    { MegaCopyIconButton(contentDescription = "Copy extended public key", getTextToCopy = { currentResult.extendedPublicKey }) }
                } else {
                    null
                },
            ) {
                MegaMonoText(currentResult.extendedPublicKey)
            }

            MegaCard(title = "QR code (public account data only)") {
                MegaQrCode(currentResult.extendedPublicKey)
            }

            MegaCard(
                title = "First receive address (external chain, index 0)",
                leadingAction = if (allowSeedCopy) {
                    { MegaCopyIconButton(contentDescription = "Copy first receive address", getTextToCopy = { currentResult.firstReceiveAddress }) }
                } else {
                    null
                },
            ) {
                MegaMonoText(currentResult.firstReceiveAddress)
            }
        }
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
