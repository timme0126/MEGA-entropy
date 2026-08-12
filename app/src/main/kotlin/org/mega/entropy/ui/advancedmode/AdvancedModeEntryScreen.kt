package org.mega.entropy.ui.advancedmode

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Advanced Mode's landing page (spec "Advanced Mode workflow"): pick how
 * an existing (not dice-generated) seed phrase gets into MEGA — type it
 * in by hand (AdvancedModeMnemonicEntryScreen), or import an already-saved
 * session's words (AdvancedModeImportPickerScreen). Only reachable when
 * Advanced Mode is on, itself gated behind the confirmation dialog in
 * Saved Session Settings — the warning here is a quieter, always-visible
 * reminder of the same risk, not the first (or only) time the user sees
 * it. Shown once here rather than repeated on each of the sub-screens,
 * since it applies equally to all of them.
 */
@Composable
fun AdvancedModeEntryScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onManualEntry: () -> Unit,
    onImportFromSavedSession: () -> Unit,
    onMultisigVaults: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    MegaInfoScaffold(title = "Advanced Mode", onBack = onBack) {
        MegaCard {
            Text(
                "Entering an existing seed phrase on any connected Android device can " +
                    "expose the funds it controls if the device is compromised. Prefer an " +
                    "offline GrapheneOS phone for sensitive seed workflows.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
                fontWeight = FontWeight.SemiBold,
            )
        }

        MegaPrimaryButton(text = "Manual Seed Word Entry", onClick = onManualEntry)
        MegaPrimaryButton(text = "Import from Saved Session", onClick = onImportFromSavedSession)
        // Deliberately does not require a seed to already be loaded first —
        // unlike every button above, a multisig vault's cosigners are each
        // filled independently from within that flow itself (saved
        // session, pasted fragment, or eventually a scan), so there's
        // nothing to type or import up front here. Routes to the saved
        // vaults list if any exist, or straight into setup otherwise — see
        // MegaNavGraph's enterMultisigVaultsEntry.
        MegaPrimaryButton(text = "Multi-Signature Vaults", onClick = onMultisigVaults)
    }
}
