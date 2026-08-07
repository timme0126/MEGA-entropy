package org.mega.entropy.ui.entropy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.mega.entropycore.Entropy256

/** Spec section 10, steps 1–2: display E as 32 bytes / 256 bits / 64 hex chars. */
@Composable
fun Entropy256Screen(
    entropy: Entropy256,
    onContinue: () -> Unit,
) {
    SecureScreen()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("256-Bit Entropy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "E = X mod 2²⁵⁶ — the last step that touches X directly. " +
                "Everything from here on (the checksum, the word indices) is a " +
                "deterministic function of these exact 32 bytes.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard(title = "E, as 64 hex characters (32 bytes, 256 bits)") {
            MegaMonoText(entropy.hex.chunked(8).joinToString(" "))
        }

        MegaCard(title = "E, as bytes") {
            val hexBytes = entropy.bytes.map { "%02X".format(it.toInt() and 0xFF) }
            MegaMonoText(hexBytes.chunked(8).joinToString("\n") { row -> row.joinToString(" ") })
        }

        MegaPrimaryButton(text = "Continue", onClick = onContinue)
    }
}
