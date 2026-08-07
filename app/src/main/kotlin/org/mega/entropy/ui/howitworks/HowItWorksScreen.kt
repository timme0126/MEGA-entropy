package org.mega.entropy.ui.howitworks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaSection

/** Spec section 25, "Educational How It Works section". */
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    MegaInfoScaffold(title = "How It Works", onBack = onBack) {
        MegaSection(
            heading = "Why 100 rolls?",
            body = "A fair six-sided die has 6 possible outcomes per roll, or " +
                "log₂(6) ≈ 2.585 bits of information. 100 rolls " +
                "therefore contain log₂(6¹⁰⁰) ≈ 258.5 " +
                "bits — enough raw material to extract an unbiased " +
                "256-bit value using rejection sampling (below). 99 rolls " +
                "would not be enough: log₂(6⁹⁹) ≈ 256.0 " +
                "bits, which is too close to the 256 we need to guarantee an " +
                "unbiased extraction with room to spare.",
        )
        MegaSection(
            heading = "Why map 1–6 to 0–5?",
            body = "Positional number systems need digits starting at 0. A " +
                "die shows 1 through 6; base-6 digits run 0 through 5. " +
                "Subtracting 1 from each roll is the only change MEGA makes " +
                "to your raw outcomes, and it's a fixed relabeling, not a " +
                "randomizing step.",
        )

        MegaCard(title = "Why rejection sampling? A toy example") {
            Text(
                "Suppose you had a fair 3-sided die (outcomes 0, 1, 2) and " +
                    "wanted a fair coin flip from it by computing outcome mod 2:",
            )
            MegaMonoText("0 mod 2 = 0")
            MegaMonoText("1 mod 2 = 1")
            MegaMonoText("2 mod 2 = 0")
            Text(
                "0 comes up twice as often as 1 — that's modulo bias. " +
                    "The fix is to reject the outcome that breaks the " +
                    "symmetry (here, reject 2 and re-roll) so only 0 and 1 " +
                    "remain, each with equal probability.",
            )
            Text(
                "100 dice rolls give 6¹⁰⁰ possible sequences, " +
                    "which is not an exact multiple of 2²⁵⁶. " +
                    "Taking X mod 2²⁵⁶ directly would make " +
                    "some 256-bit outputs slightly more likely than others " +
                    "— the same bias as the toy example, just with " +
                    "much bigger numbers. MEGA instead computes the largest " +
                    "multiple of 2²⁵⁶ that fits inside " +
                    "6¹⁰⁰, calls that the threshold T, and " +
                    "rejects any sequence whose value X is ≥ T. Every " +
                    "value below T falls into exactly one of five complete, " +
                    "equal-sized blocks of 2²⁵⁶ possibilities " +
                    "— so X mod 2²⁵⁶ is exactly uniform " +
                    "over the accepted sequences.",
            )
        }

        MegaSection(
            heading = "Why SHA-256?",
            body = "BIP39 specifies that a mnemonic's checksum is the first " +
                "ENT/32 bits of the SHA-256 hash of the entropy (ENT = " +
                "entropy length in bits). For 256-bit entropy that's an " +
                "8-bit checksum. SHA-256 here is used purely as a fixed, " +
                "deterministic function of your entropy — the same " +
                "entropy always produces the same checksum. It never adds " +
                "randomness of its own.",
        )

        MegaCard(title = "Why 24 words?") {
            MegaMonoText("ENT = 256")
            MegaMonoText("CS  = ENT / 32 = 8")
            MegaMonoText("ENT + CS = 256 + 8 = 264")
            MegaMonoText("264 / 11 = 24")
            Text("BIP39 splits the entropy+checksum bitstream into 11-bit groups, so the total bit count must divide evenly by 11. 264 does, giving 24 words.")
        }

        MegaCard(title = "Why 2048 words?") {
            MegaMonoText("2¹¹ = 2048")
            Text("Each word encodes one 11-bit value, and 11 bits can represent exactly 2048 distinct values — so the official BIP39 word list has exactly 2048 entries, one per possible value.")
        }
    }
}
