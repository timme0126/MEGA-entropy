package org.mega.entropy.ui.checksum

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
import org.mega.entropycore.ChecksumResult

/** Spec section 10, steps 3–7: SHA-256(E), the first 8 bits, and 256+8=264. */
@Composable
fun ChecksumScreen(
    checksum: ChecksumResult,
    onContinue: () -> Unit,
) {
    val digestHex = checksum.digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    val checksumBitsString = checksum.checksumBits.joinToString("") { if (it) "1" else "0" }

    SecureScreen()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SHA-256 Checksum", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "BIP39 uses SHA-256 purely as a deterministic function of the " +
                "entropy — the same E always produces the same digest. It " +
                "adds no randomness of its own; see How It Works.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard(title = "SHA-256(E) — full digest") {
            MegaMonoText(digestHex.chunked(8).joinToString(" "))
        }

        MegaCard(title = "First 8 bits of the digest → checksum") {
            MegaMonoText(checksumBitsString)
            Text(
                "These 8 bits are BIP39's checksum for 256-bit entropy " +
                    "(ENT/32 = 256/32 = 8).",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        MegaCard(title = "Entropy + checksum") {
            MegaMonoText("256 entropy bits + 8 checksum bits = 264 bits")
        }

        MegaPrimaryButton(text = "Continue", onClick = onContinue)
    }
}
