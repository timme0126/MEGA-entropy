package org.mega.entropy.ui.chooselength

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropycore.MnemonicLength

/**
 * Lets the user pick a 12-word (128-bit, 50 rolls) or 24-word (256-bit,
 * 100 rolls) mnemonic before rolling begins. 24 words is the BIP39 default
 * most wallets expect and MEGA's original design, so it's the primary
 * button; 12 words is offered as a faster, still-standard alternative.
 */
@Composable
fun ChooseLengthScreen(
    onBack: () -> Unit,
    onLengthChosen: (MnemonicLength) -> Unit,
) {
    MegaInfoScaffold(title = "Mnemonic Length", onBack = onBack) {
        Text(
            "Both lengths are standard BIP39 mnemonics accepted by wallets. " +
                "24 words is the more common default; 12 words is a valid, " +
                "faster-to-enter alternative with less (but still very " +
                "large) entropy.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard(title = "24 Words") {
            Text(
                "256 bits of entropy, from 100 dice rolls in 20 batches of 5.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MegaPrimaryButton(
            text = "24 Words (100 Rolls)",
            onClick = { onLengthChosen(MnemonicLength.TWENTY_FOUR_WORDS) },
        )

        MegaCard(title = "12 Words") {
            Text(
                "128 bits of entropy, from 50 dice rolls in 10 batches of 5.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MegaSecondaryButton(
            text = "12 Words (50 Rolls)",
            onClick = { onLengthChosen(MnemonicLength.TWELVE_WORDS) },
        )
    }
}
