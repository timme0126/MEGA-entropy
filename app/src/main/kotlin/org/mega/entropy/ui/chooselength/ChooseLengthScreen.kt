package org.mega.entropy.ui.chooselength

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropycore.MnemonicLength

/**
 * Lets the user pick a 12-word (128-bit, 50 rolls) or 24-word (256-bit,
 * 100 rolls) mnemonic before rolling begins. Both are standard, fully
 * valid BIP39 mnemonics — presented as two equal, tappable choices rather
 * than one "primary" and one "alternative", since MEGA has no opinion on
 * which a given wallet expects.
 */
@Composable
fun ChooseLengthScreen(
    onBack: () -> Unit,
    onLengthChosen: (MnemonicLength) -> Unit,
) {
    MegaInfoScaffold(title = "Mnemonic Length", onBack = onBack) {
        Text("Choose one:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        LengthOptionCard(
            title = "24 Words",
            detail = "256 bits of entropy, from 100 dice rolls in 20 batches of 5.",
            onClick = { onLengthChosen(MnemonicLength.TWENTY_FOUR_WORDS) },
        )
        LengthOptionCard(
            title = "12 Words",
            detail = "128 bits of entropy, from 50 dice rolls in 10 batches of 5.",
            onClick = { onLengthChosen(MnemonicLength.TWELVE_WORDS) },
        )
    }
}

@Composable
private fun LengthOptionCard(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
