package org.mega.entropy.ui.splitgroups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropycore.WordDerivation

/** Spec section 10 step 8 / section 11 lead-in: show the 264-bit stream as
 * 24 consecutive 11-bit groups before drilling into each word individually. */
@Composable
fun SplitGroupsScreen(
    derivations: List<WordDerivation>,
    onContinue: () -> Unit,
) {
    SecureScreen()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val totalBits = derivations.size * 11
        Text("Split Into ${derivations.size} Groups", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "$totalBits bits ÷ 11 bits per group = ${derivations.size} groups. Each " +
                "group becomes one word — the full derivation for each is on " +
                "the next screen.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard {
            derivations.forEach { d ->
                val bitsString = d.bits.joinToString("") { if (it) "1" else "0" }
                MegaMonoText("Group ${(d.groupIndex + 1).toString().padStart(2, '0')}:  $bitsString")
                if (d.groupIndex != derivations.lastIndex) HorizontalDivider()
            }
        }

        MegaPrimaryButton(text = "Continue", onClick = onContinue)
    }
}
