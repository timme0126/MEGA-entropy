package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.MultisigCosignerOrigin
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.deriveMultisigCosignerAccountKeys
import org.mega.entropycore.parseCosignerDescriptorFragment

/**
 * Derives a cosigner key for one multisig vault slot from a saved
 * session's words, using the vault's already-chosen network/script type
 * (a cosigner's script type/network are wallet-wide policy, not a
 * per-slot choice — see AdvancedModeMultisigVaultScreen's Policy step).
 *
 * Deliberately never displays [mnemonicWords] anywhere on screen — unlike
 * AdvancedModeHubScreen's "Reveal Seed Words", picking a cosigner source
 * has no reason to show the words themselves, only to derive a public key
 * from them. [mnemonicWords] and the locally-typed passphrase both exist
 * only for the moment "Derive Cosigner Key" is pressed; the caller
 * (MegaNavGraph) is expected to clear whatever nav-graph state supplied
 * mnemonicWords as soon as this screen is left (success or back) — see
 * the DisposableEffect at this route's composable() wiring — the same
 * "don't outlive the screen that needed it" lifetime every other
 * passphrase field in Advanced Mode already follows.
 */
@Composable
fun AdvancedModeMultisigDeriveCosignerScreen(
    mnemonicWords: List<String>,
    sourceLabel: String,
    network: WalletNetwork,
    scriptType: MultisigScriptType,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onDerived: (origin: MultisigCosignerOrigin, label: String, passphraseUsed: Boolean) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var accountText by remember { mutableStateOf("0") }
    var error by remember { mutableStateOf<String?>(null) }

    MegaInfoScaffold(title = "Add Cosigner", onBack = onBack) {
        MegaCard {
            Text(
                text = if (sourceLabel.isBlank()) "Deriving from a saved session." else "Deriving from: $sourceLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Only the resulting public key leaves this screen — the seed words themselves are never shown or copied here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MegaCard(title = "Passphrase (optional)") {
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    error = null
                },
                label = { Text("BIP39 passphrase, if used") },
                singleLine = true,
                visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
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
                text = "Leave blank if this session didn't use one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = accountText,
            onValueChange = { value ->
                accountText = value.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                error = null
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
            text = "Derive Cosigner Key",
            onClick = {
                val account = accountText.toIntOrNull()
                if (account == null) {
                    error = "Enter a valid account index."
                } else {
                    try {
                        val keys = deriveMultisigCosignerAccountKeys(mnemonicWords, passphrase, scriptType, network, account)
                        // Round-trip through the same fragment format/parser every other
                        // cosigner source (paste, scan) produces its MultisigCosignerOrigin
                        // through, rather than hand-constructing one here — one code path
                        // for "text -> validated origin" everywhere in the vault flow.
                        val fragment = "[${keys.masterFingerprint}/${keys.derivationPath.removePrefix("m/")}]${keys.extendedPublicKey}"
                        val origin = parseCosignerDescriptorFragment(fragment)
                        val label = if (sourceLabel.isBlank()) keys.masterFingerprint else sourceLabel
                        val passphraseUsed = passphrase.isNotEmpty()
                        passphrase = ""
                        onDerived(origin, label, passphraseUsed)
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Could not derive a cosigner key from this session."
                    }
                }
            },
        )
    }
}
