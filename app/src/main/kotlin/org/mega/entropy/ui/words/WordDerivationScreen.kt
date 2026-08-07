package org.mega.entropy.ui.words

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropycore.WordDerivation

/**
 * Spec section 11, "24-word explanation screen", and section 12, hex
 * representation. Every word is traceable back to its 11-bit group before
 * the mnemonic itself is ever shown — see FinalMnemonicScreen.
 */
@Composable
fun WordDerivationScreen(
    derivations: List<WordDerivation>,
    onContinue: () -> Unit,
) {
    SecureScreen()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Word Derivation", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "dice → base-6 digits → chunks → X → E → SHA-256 → checksum → " +
                "11-bit group → index → word. Tap any word to see its trace.",
            style = MaterialTheme.typography.bodyMedium,
        )

        derivations.forEach { derivation ->
            WordDerivationCard(derivation)
        }

        MegaPrimaryButton(text = "Continue", onClick = onContinue)
    }
}

@Composable
private fun WordDerivationCard(derivation: WordDerivation) {
    var expanded by remember { mutableStateOf(false) }
    val bitsString = derivation.bits.joinToString("") { if (it) "1" else "0" }
    val hexIndex = "%03X".format(derivation.decimalIndex)
    val wordNumber = (derivation.groupIndex + 1).toString().padStart(2, '0')

    MegaCard {
        Column(
            modifier = Modifier.clickable { expanded = !expanded },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Word $wordNumber", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(derivation.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MegaMonoText("11 bits:        $bitsString")
                MegaMonoText("Decimal index:  ${derivation.decimalIndex}")
                MegaMonoText("Hex index:      $hexIndex")
                MegaMonoText("Word:           ${derivation.word}")
            }
        }
        Text(
            text = if (expanded) "Hide calculation" else "Show calculation",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}
