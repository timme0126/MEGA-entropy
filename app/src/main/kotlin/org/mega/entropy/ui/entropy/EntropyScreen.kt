package org.mega.entropy.ui.entropy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import org.mega.entropycore.MnemonicEntropy

/** Spec section 10, steps 1–2: display E as its exact byte/bit/hex length. */
@Composable
fun EntropyScreen(
    entropy: MnemonicEntropy,
    onContinue: () -> Unit,
) {
    SecureScreen()
    val bits = entropy.bytes.size * 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("$bits-Bit Entropy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "E = X mod 2^$bits — the last step that touches X directly. " +
                "Everything from here on (the checksum, the word indices) is a " +
                "deterministic function of these exact ${entropy.bytes.size} bytes.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard(title = "E, as ${entropy.hex.length} hex characters (${entropy.bytes.size} bytes, $bits bits)") {
            MegaMonoText(entropy.hex.chunked(8).joinToString(" "))
        }

        MegaCard(title = "E, as bytes") {
            val hexBytes = entropy.bytes.map { "%02X".format(it.toInt() and 0xFF) }
            MegaMonoText(hexBytes.chunked(8).joinToString("\n") { row -> row.joinToString(" ") })
        }

        MegaPrimaryButton(text = "Continue", onClick = onContinue)
    }
}
