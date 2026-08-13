package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.mega.entropy.storage.SavedMultisigCosigner
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen

@Composable
fun SavedVaultCosignerPickScreen(
    vaultLabel: String,
    cosigners: List<SavedMultisigCosigner>,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onCosignerSelected: (SavedMultisigCosigner) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    MegaInfoScaffold(title = "Select Cosigner", onBack = onBack) {
        MegaCard {
            Text(
                text = "Select which cosigner \"$vaultLabel\" this device will act as. MEGA will verify the saved session you choose actually matches before signing anything — do not guess.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        cosigners.forEachIndexed { index, cosigner ->
            MegaCard(title = cosigner.label.ifBlank { "Cosigner ${index + 1}" }) {
                MegaMonoText("Fingerprint: ${cosigner.masterFingerprint}")
                MegaMonoText("Path: ${cosigner.derivationPath}")
                MegaPrimaryButton(text = "Select This Cosigner", onClick = { onCosignerSelected(cosigner) })
            }
        }
    }
}
