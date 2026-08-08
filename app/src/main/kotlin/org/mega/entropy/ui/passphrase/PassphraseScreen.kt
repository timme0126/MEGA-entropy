package org.mega.entropy.ui.passphrase

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.Bip39Seed
import org.mega.entropycore.deriveSeed

/**
 * Optional step after FinalMnemonicScreen: attaches a BIP39 "25th word"
 * passphrase to the mnemonic MEGA just derived from dice, per BIP-0039 —
 * see deriveSeed's KDoc for why this only ever operates on words this app
 * derived itself, never arbitrary typed-in ones. Nothing computed here
 * (passphrase or seed) is ever saved to disk, saved to SavedStateHandle,
 * or passed to any other screen — the seed exists only in this
 * composable's memory for as long as it's on screen, exactly like the
 * mnemonic itself on FinalMnemonicScreen.
 */
@Composable
fun PassphraseScreen(
    words: List<String>,
    onContinue: () -> Unit,
) {
    SecureScreen()
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var calculatedSeed by remember { mutableStateOf<Bip39Seed?>(null) }
    var seedRevealed by remember { mutableStateOf(false) }

    val mismatch = confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase
    val wordCountLabel = "${words.size} words"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Optional: Add a Passphrase", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        MegaCard {
            Text(
                "A passphrase (sometimes called a \"25th word\") is an extra " +
                    "string combined with your $wordCountLabel to derive the " +
                    "final wallet seed. The same words with a different " +
                    "passphrase — including no passphrase at all — produce a " +
                    "completely different, unrelated wallet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MegaCard {
            Text(
                "If you forget this passphrase, the wallet it protects is " +
                    "permanently unrecoverable — even with the correct " +
                    "$wordCountLabel. There is no reset. MEGA never saves it " +
                    "anywhere; you must remember it yourself, ideally " +
                    "separately from where you keep the words.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (calculatedSeed == null) {
            val visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirmPassphrase,
                onValueChange = { confirmPassphrase = it },
                label = { Text("Confirm Passphrase") },
                singleLine = true,
                isError = mismatch,
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
            if (mismatch) {
                Text("Passphrases don't match.", style = MaterialTheme.typography.bodySmall, color = MegaError)
            }
            Text(
                text = if (showPassphrase) "Hide passphrase" else "Show passphrase",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showPassphrase = !showPassphrase },
            )

            MegaPrimaryButton(
                text = "Calculate Seed",
                enabled = passphrase.isNotEmpty() && !mismatch,
                onClick = { calculatedSeed = deriveSeed(words, passphrase) },
            )
            MegaSecondaryButton(text = "Skip (No Passphrase)", onClick = onContinue)
        } else {
            val strengthBits = remember(passphrase) { estimatePassphraseStrengthBits(passphrase) }
            MegaCard(title = "Passphrase Strength (Estimate)") {
                Text(
                    "~$strengthBits bits — an upper bound assuming every " +
                        "character was random. A guessable phrase (real words, " +
                        "names, patterns) is far weaker than this number " +
                        "suggests.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!seedRevealed) {
                MegaPrimaryButton(text = "Reveal BIP39 Seed", onClick = { seedRevealed = true })
            } else {
                MegaCard(title = "BIP39 Seed (512-bit, hex)") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        calculatedSeed?.hex?.chunked(32)?.forEach { line ->
                            MegaMonoText(line)
                        }
                    }
                }
            }

            Text(
                "This seed isn't saved anywhere by MEGA. To reproduce it " +
                    "later, you need both the exact $wordCountLabel and this " +
                    "exact passphrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Start over",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    calculatedSeed = null
                    seedRevealed = false
                    passphrase = ""
                    confirmPassphrase = ""
                },
            )
            MegaPrimaryButton(text = "Continue", onClick = onContinue)
        }
    }
}
