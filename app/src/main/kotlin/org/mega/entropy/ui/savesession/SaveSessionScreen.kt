package org.mega.entropy.ui.savesession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Spec section 16, "Save?" step. Saving is always explicit, defaults to
 * dice-only, and saving the derived mnemonic requires a second, separate
 * confirmation on top of picking that option in the first place.
 *
 * hasPendingPassphrase reflects whether a passphrase was entered on the
 * preceding (optional) PassphraseScreen; when true, an additional opt-in
 * checkbox offers to also save a PassphraseCheck for it, independent of
 * whether the mnemonic itself is saved (a check can always be verified
 * later since the mnemonic is recomputable from saved dice rolls).
 */
@Composable
fun SaveSessionScreen(
    rollCount: Int,
    wordCount: Int,
    hasPendingPassphrase: Boolean,
    onDontSave: () -> Unit,
    onSaveDiceOnly: (savePassphraseCheck: Boolean) -> Unit,
    onSaveDiceAndMnemonic: (savePassphraseCheck: Boolean) -> Unit,
) {
    SecureScreen()
    var confirmingMnemonicSave by remember { mutableStateOf(false) }
    var savePassphraseCheck by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Save This Session?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "By default, MEGA keeps nothing after you leave this screen. " +
                "Saving is entirely optional and always something you choose.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (hasPendingPassphrase) {
            MegaCard(title = "Also Save a Passphrase Check") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Save a way to verify the passphrase you just entered " +
                            "later, without MEGA ever storing or displaying " +
                            "the passphrase itself. Only applies if you save " +
                            "this session below.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePassphraseCheck, onCheckedChange = { savePassphraseCheck = it })
                        Text("Save a passphrase check", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (!confirmingMnemonicSave) {
            MegaCard(title = "Don't Save") {
                Text(
                    "Nothing is written to disk. If you already recorded your " +
                        "words by hand, this is the safest option.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MegaSecondaryButton(text = "Don't Save", onClick = onDontSave)

            MegaCard(title = "Save Dice Rolls") {
                Text(
                    "Saves only your $rollCount physical dice results, " +
                        "encrypted on this device. The mnemonic is " +
                        "recalculated from the dice each time you reopen " +
                        "this session — nothing about the words themselves " +
                        "is stored.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MegaSecondaryButton(text = "Save Dice Rolls", onClick = { onSaveDiceOnly(savePassphraseCheck) })

            MegaCard(title = "Save Dice Rolls + Derived Mnemonic") {
                Text(
                    "Also saves the $wordCount-word mnemonic itself, " +
                        "encrypted alongside the dice. This increases the " +
                        "amount of sensitive data stored on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError,
                )
            }
            MegaSecondaryButton(
                text = "Save Dice Rolls + Derived Mnemonic",
                onClick = { confirmingMnemonicSave = true },
            )
        } else {
            MegaCard {
                Text(
                    "Are you sure? Saving the mnemonic means your $wordCount " +
                        "words will exist encrypted on this device, in " +
                        "addition to wherever you've already written them " +
                        "down. Anyone who defeats both this device's " +
                        "encryption and your MEGA PIN (if enabled) could " +
                        "recover them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            MegaPrimaryButton(
                text = "Yes, Save the Mnemonic Too",
                onClick = { onSaveDiceAndMnemonic(savePassphraseCheck) },
            )
            MegaSecondaryButton(text = "Cancel", onClick = { confirmingMnemonicSave = false })
        }
    }
}
