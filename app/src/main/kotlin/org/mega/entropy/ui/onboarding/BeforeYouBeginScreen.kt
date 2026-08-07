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

/** Spec section 24, "Before You Begin". */
@Composable
fun BeforeYouBeginScreen(
    onBack: () -> Unit,
    onStartRolling: () -> Unit,
) {
    MegaInfoScaffold(title = "Before You Begin", onBack = onBack) {
        MegaSection(
            heading = "Use a fair six-sided die",
            body = "An ordinary d6 you trust to be unweighted and undamaged. " +
                "MEGA cannot verify the fairness of your physical die — that's " +
                "on you (see Security Model).",
        )
        MegaSection(
            heading = "Roll it physically, 100 times",
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
            heading = "100 rolls are needed",
            body = "Entered in 20 batches of 5, so you're never staring at a " +
                "wall of fields.",
        )
        MegaSection(
            heading = "About 1 in 8 sequences gets rejected",
            body = "Roughly 11–12% of mathematically valid 100-roll sequences " +
                "will fail the bias check and must be rerolled completely, " +
                "from roll 1. This is expected and is what keeps the result " +
                "unbiased — see How It Works for why.",
        )
        MegaSection(
            heading = "Don't photograph the final seed",
            body = "Your 24-word phrase controls real funds if you use it. " +
                "Camera rolls, cloud photo backup, and screenshots are all " +
                "attack surface MEGA can't protect you from.",
        )

        Spacer(modifier = Modifier.height(8.dp))
        Column {
            MegaPrimaryButton(text = "I Understand — Start Rolling", onClick = onStartRolling)
        }
    }
}
