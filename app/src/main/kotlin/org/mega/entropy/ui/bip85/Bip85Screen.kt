package org.mega.entropy.ui.bip85

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.Bip85DerivedMnemonic
import org.mega.entropycore.Bip85MnemonicWords
import org.mega.entropycore.deriveBip85Bip39Mnemonic

@Composable
fun Bip85Screen(
    parentWords: List<String>,
    onBack: () -> Unit,
) {
    SecureScreen()

    var selectedWords by remember { mutableStateOf(Bip85MnemonicWords.TWELVE) }
    var indexText by remember { mutableStateOf("0") }
    var parentPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Bip85DerivedMnemonic?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun clearResult() {
        result = null
        revealed = false
        error = null
    }

    MegaInfoScaffold(title = "BIP85 Child Mnemonic", onBack = onBack) {
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

        MegaCard(title = "Parent passphrase") {
            OutlinedTextField(
                value = parentPassphrase,
                onValueChange = {
                    parentPassphrase = it
                    clearResult()
                },
                label = { Text("Parent BIP39 passphrase, if used") },
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
            text = "Calculate BIP85 Child",
            onClick = {
                val index = indexText.toLongOrNull()
                if (index == null || index !in 0..2_147_483_647L) {
                    error = "Index must be between 0 and 2147483647."
                    result = null
                    revealed = false
                } else {
                    try {
                        result = deriveBip85Bip39Mnemonic(
                            parentWords = parentWords,
                            childWords = selectedWords,
                            index = index,
                            parentPassphrase = parentPassphrase,
                        )
                        revealed = false
                        error = null
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Could not derive BIP85 child mnemonic."
                        result = null
                        revealed = false
                    }
                }
            },
        )

        val currentResult = result
        if (currentResult != null) {
            MegaCard(title = "Derivation path") {
                MegaMonoText(currentResult.path)
            }
            if (!revealed) {
                MegaSecondaryButton(
                    text = "Reveal Child Entropy and ${currentResult.words.wordCount} Words",
                    onClick = { revealed = true },
                )
            } else {
                MegaCard(title = "Child entropy") {
                    MegaMonoText(currentResult.entropy.hex)
                }
                MegaCard(title = "Child mnemonic") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        currentResult.mnemonicWords.forEachIndexed { index, word ->
                            MegaMonoText("${(index + 1).toString().padStart(2, '0')}. $word")
                        }
                    }
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
