package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.ManualMnemonicValidation
import org.mega.entropycore.validateManualMnemonic

/**
 * Entry point for Advanced Mode (spec "Advanced Mode workflow"): manually
 * typing in an existing BIP39 mnemonic to derive BIP85 children or wallet
 * account keys from it, rather than one MEGA generated from dice. Only
 * reachable when Advanced Mode is on, itself gated behind the confirmation
 * dialog in Saved Session Settings — the warning here is a quieter, always-
 * visible reminder of the same risk, not the first (or only) time the user
 * sees it.
 */
@Composable
fun AdvancedModeMnemonicEntryScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onValidated: (words: List<String>, passphrase: String) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    var wordCount by remember { mutableStateOf(12) }
    var wordFields by remember(wordCount) { mutableStateOf(List(wordCount) { "" }) }
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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

        MegaCard(title = "Word count") {
            WordCountOption(
                label = "12 words",
                selected = wordCount == 12,
                onClick = { wordCount = 12; error = null },
            )
            WordCountOption(
                label = "24 words",
                selected = wordCount == 24,
                onClick = { wordCount = 24; error = null },
            )
        }

        MegaCard(title = "Seed words") {
            wordFields.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pair.forEachIndexed { colIndex, word ->
                        val index = rowIndex * 2 + colIndex
                        OutlinedTextField(
                            value = word,
                            onValueChange = { value ->
                                wordFields = wordFields.toMutableList().also { it[index] = value }
                                error = null
                            },
                            label = { Text((index + 1).toString()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        MegaCard(title = "Passphrase (optional)") {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
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
        }

        val currentError = error
        if (currentError != null) {
            Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
        }

        MegaPrimaryButton(
            text = "Validate & Continue",
            onClick = {
                when (val validation = validateManualMnemonic(wordFields)) {
                    is ManualMnemonicValidation.Valid -> {
                        error = null
                        onValidated(validation.words, passphrase)
                    }
                    is ManualMnemonicValidation.Invalid -> {
                        error = validation.reason
                    }
                }
            },
        )
    }
}

@Composable
private fun WordCountOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
