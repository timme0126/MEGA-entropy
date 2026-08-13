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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.mega.entropy.storage.SavedMultisigCosigner
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.normalizeMasterFingerprint
import org.mega.entropycore.masterKeyFingerprint

@Composable
fun SavedVaultCosignerVerifyScreen(
    mnemonicWords: List<String>,
    sourceLabel: String,
    selectedCosigner: SavedMultisigCosigner,
    allVaultCosigners: List<SavedMultisigCosigner>,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onVerified: (passphrase: String) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    MegaInfoScaffold(title = "Verify Cosigner", onBack = onBack) {
        MegaCard(title = "Selected Cosigner") {
            Text(
                text = selectedCosigner.label.ifBlank { "Cosigner" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            MegaMonoText("Fingerprint: ${selectedCosigner.masterFingerprint}")
            MegaMonoText("Path: ${selectedCosigner.derivationPath}")
        }

        MegaCard {
            Text(
                text = if (sourceLabel.isBlank()) "Verifying against a saved session." else "Verifying against: $sourceLabel",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Only the resulting fingerprint is checked — the seed words themselves are never shown or copied here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (showPassphrase) "Hide passphrase" else "Show passphrase",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showPassphrase = !showPassphrase }
            )
            Text(
                text = "Leave blank if this session didn't use one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (error != null) {
            Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MegaError)
        }

        MegaPrimaryButton(
            text = "Verify & Continue",
            onClick = {
                try {
                    val actualFingerprint = masterKeyFingerprint(mnemonicWords, passphrase)
                    val expectedFingerprint = normalizeMasterFingerprint(selectedCosigner.masterFingerprint)
                    if (actualFingerprint == expectedFingerprint) {
                        onVerified(passphrase)
                    } else {
                        val actualMatch = allVaultCosigners.firstOrNull { other ->
                            runCatching { normalizeMasterFingerprint(other.masterFingerprint) }.getOrNull() == actualFingerprint
                        }
                        error = if (actualMatch != null) {
                            "This session's key matches \"${actualMatch.label.ifBlank { "another cosigner" }}\", not \"${selectedCosigner.label.ifBlank { "this cosigner" }}\". Go back and select the correct cosigner, or choose a different saved session."
                        } else {
                            "This session's key does not match \"${selectedCosigner.label.ifBlank { "this cosigner" }}\" (or any other cosigner in this vault). MEGA cannot sign as this cosigner with the selected session and passphrase."
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    error = e.message ?: "Could not verify this cosigner."
                }
            }
        )
    }
}
