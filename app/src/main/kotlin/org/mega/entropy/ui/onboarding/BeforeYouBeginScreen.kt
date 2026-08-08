package org.mega.entropy.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSection
import org.mega.entropycore.MnemonicLength

/** Spec section 24, "Before You Begin". */
@Composable
fun BeforeYouBeginScreen(
    mnemonicLength: MnemonicLength,
    onBack: () -> Unit,
    onStartRolling: () -> Unit,
) {
    val rollCount = mnemonicLength.rollCount
    val batchCount = rollCount / 5
    // Approximate rejection rate, shown to one decimal place: ~11.4% for
    // 24 words (100 rolls), ~15.8% for 12 words (50 rolls) — see
    // docs/ENTROPY-MATH.md for the exact derivation.
    val rejectionRateText = if (mnemonicLength == MnemonicLength.TWELVE_WORDS) "about 16%" else "about 11%"

    MegaInfoScaffold(title = "Before You Begin", onBack = onBack) {
        MegaSection(
            heading = "Use a fair six-sided die",
            body = "An ordinary d6 you trust to be unweighted and undamaged. " +
                "MEGA cannot verify the fairness of your physical die — that's " +
                "on you (see Security Model).",
        )
        MegaSection(
            heading = "Roll it physically, $rollCount times",
            body = "Not a die-rolling app, not a simulator. A real die, rolled " +
                "by you, one outcome at a time.",
        )
        MegaSection(
            heading = "Enter outcomes exactly as rolled",
            body = "Enter the number that landed face-up. If you make a " +
                "mistake, use Undo — never \"round\" a result or enter what " +
                "you expected instead of what you saw.",
        )
        MegaSection(
            heading = "$rollCount rolls are needed",
            body = "Entered in $batchCount batches of 5, so you're never " +
                "staring at a wall of fields.",
        )
        MegaSection(
            heading = "$rejectionRateText of sequences get rejected",
            body = "That fraction of mathematically valid $rollCount-roll " +
                "sequences will fail the bias check and must be rerolled " +
                "completely, from roll 1. This is expected and is what keeps " +
                "the result unbiased — see How It Works for why.",
        )
        MegaSection(
            heading = "Don't photograph the final seed",
            body = "Your ${mnemonicLength.wordCount}-word phrase controls real " +
                "funds if you use it. Camera rolls, cloud photo backup, and " +
                "screenshots are all attack surface MEGA can't protect you from.",
        )

        Spacer(modifier = Modifier.height(8.dp))
        Column {
            MegaPrimaryButton(text = "I Understand — Start Rolling", onClick = onStartRolling)
        }
    }
}
